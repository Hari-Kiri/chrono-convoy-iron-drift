package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/chrono-convoy/backend/internal/actor"
	"github.com/chrono-convoy/backend/internal/ledger"
	"github.com/chrono-convoy/backend/internal/mongo"
	"github.com/chrono-convoy/backend/internal/replay"
)

func setupTestServer(t *testing.T) (*Server, *replay.KeyRing) {
	t.Helper()
	ctx := context.Background()
	sup := actor.NewRegionMarketSupervisor(ctx, 256, 60*time.Second)
	t.Cleanup(func() {
		sup.Shutdown()
	})

	kr := replay.NewKeyRing("chrono-key-2026-v1", "chrono-convoy-iron-drift-sprint1-master-secret-2026")
	verifier := replay.NewVerifier(kr)

	repo, err := ledger.NewPostgresLedgerRepository("postgres://box@localhost:5432/chrono_convoy?sslmode=disable")
	if err != nil {
		t.Fatalf("failed to connect to PostgreSQL chrono_convoy: %v", err)
	}
	t.Cleanup(func() {
		_ = repo.Close()
	})

	docStore := mongo.NewDocumentStore()
	srv := NewServer(sup, verifier, repo, docStore)
	return srv, kr
}

func doJSONRequest(t *testing.T, srv *Server, method, path, playerID string, body any) (*http.Response, []byte) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("json.Marshal failed: %v", err)
		}
		reader = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, path, reader)
	req.Header.Set("Content-Type", "application/json")
	if playerID != "" {
		req.Header.Set("X-Player-ID", playerID)
	}
	resp, err := srv.App().Test(req, 5000)
	if err != nil {
		t.Fatalf("App().Test(%s %s) failed: %v", method, path, err)
	}
	respBytes, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	return resp, respBytes
}

func TestServer_EndToEndEndpointsAndSecurity(t *testing.T) {
	srv, kr := setupTestServer(t)

	// 1. GET /healthz
	resp, body := doJSONRequest(t, srv, http.MethodGet, "/healthz", "", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /healthz expected 200, got %d: %s", resp.StatusCode, string(body))
	}
	if resp.Header.Get("X-Content-Type-Options") != "nosniff" {
		t.Errorf("expected OWASP X-Content-Type-Options: nosniff header")
	}

	// 2. GET /api/v1/oasis/market (all 3 Oasis Hubs)
	resp, body = doJSONRequest(t, srv, http.MethodGet, "/api/v1/oasis/market", "", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/v1/oasis/market expected 200, got %d: %s", resp.StatusCode, string(body))
	}

	// 3. GET /api/v1/convoy/loadout with ABAC / Anti-IDOR enforcement
	resp, body = doJSONRequest(t, srv, http.MethodGet, "/api/v1/convoy/loadout?convoy_id=convoy_iron_drift_01", "player_cmdr_riki", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/v1/convoy/loadout expected 200, got %d: %s", resp.StatusCode, string(body))
	}
	respIDOR, _ := doJSONRequest(t, srv, http.MethodGet, "/api/v1/convoy/loadout?convoy_id=convoy_iron_drift_01", "player_attacker", nil)
	if respIDOR.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden on IDOR attempt, got %d", respIDOR.StatusCode)
	}

	// 4. Create an isolated test convoy via POST /api/v1/convoy/loadout
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	playerID := "player_api_" + suffix
	convoyID := "convoy_api_" + suffix
	resp, body = doJSONRequest(t, srv, http.MethodPost, "/api/v1/convoy/loadout", playerID, ledger.UpdateLoadoutRequest{
		ConvoyID:         convoyID,
		PlayerID:         playerID,
		LocomotiveClass:  "iron_drifter_mk1",
		MaxReactorWatts:  100,
		MaxCargoCapacity: 240,
		CoolingRodBay:    12,
		Carriages: []ledger.CarriageSlot{
			{Slot: 1, BlueprintID: "gatling_turret_car", WattDraw: 10, CargoCap: 40},
			{Slot: 2, BlueprintID: "cargo_smelter_car", WattDraw: 10, CargoCap: 200},
		},
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/convoy/loadout expected 200, got %d: %s", resp.StatusCode, string(body))
	}

	// 5. POST /api/v1/oasis/market/trade (BUY purified_water at hydro_haven with idempotency check)
	tradeReq := MarketTradeHTTPRequest{
		IdempotencyKey: "idem_api_trade_" + suffix,
		PlayerID:       playerID,
		ConvoyID:       convoyID,
		HubID:          "hydro_haven",
		CommodityID:    "purified_water",
		Action:         "BUY",
		Quantity:       15,
	}
	resp, body = doJSONRequest(t, srv, http.MethodPost, "/api/v1/oasis/market/trade", playerID, tradeReq)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/oasis/market/trade expected 200, got %d: %s", resp.StatusCode, string(body))
	}

	// Repeat exact trade request with same IdempotencyKey -> must succeed with idempotent_replay: true
	resp2, body2 := doJSONRequest(t, srv, http.MethodPost, "/api/v1/oasis/market/trade", playerID, tradeReq)
	if resp2.StatusCode != http.StatusOK || !bytes.Contains(body2, []byte(`"idempotent_replay":true`)) {
		t.Fatalf("expected idempotent_replay:true on duplicate trade, got status=%d body=%s", resp2.StatusCode, string(body2))
	}

	// 6. POST /api/v1/convoy/run/verify (valid HMAC-SHA256 replay)
	runReq := &replay.VerifyRunRequest{
		RunID:              "run_api_" + suffix,
		IdempotencyKey:     "idem_api_run_" + suffix,
		PlayerID:           playerID,
		ConvoyID:           convoyID,
		OriginHubID:        "hydro_haven",
		DestinationHubID:   "iron_refinery",
		Seed:               777888999,
		MaxReactorWatts:    100,
		InitialCoolingRods: 10,
		Actions: []replay.ReplayAction{
			{Tick: 0, Type: replay.ActionAllocateWatts, ShieldsWatts: 30, ArtilleryWatts: 35, ThrustersWatts: 35},
			{Tick: 180, Type: replay.ActionFireTurret, TargetRaiderID: "raider_01"},
			{Tick: 360, Type: replay.ActionFlushCoolingRod},
			{Tick: 600, Type: replay.ActionDeliverCargo, CommodityID: "purified_water", Quantity: 10},
		},
	}
	sig, err := kr.SignRequestHMAC(runReq)
	if err != nil {
		t.Fatalf("SignRequestHMAC failed: %v", err)
	}
	runReq.SignatureHMAC = sig

	resp, body = doJSONRequest(t, srv, http.MethodPost, "/api/v1/convoy/run/verify", playerID, runReq)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/convoy/run/verify expected 200, got %d: %s", resp.StatusCode, string(body))
	}

	// Also verify alias route POST /api/v1/run/verify returns idempotent replay
	respAlias, bodyAlias := doJSONRequest(t, srv, http.MethodPost, "/api/v1/run/verify", playerID, runReq)
	if respAlias.StatusCode != http.StatusOK || !bytes.Contains(bodyAlias, []byte(`"idempotent_replay":true`)) {
		t.Fatalf("POST /api/v1/run/verify expected 200 idempotent replay, got %d: %s", respAlias.StatusCode, string(bodyAlias))
	}

	// Tampered seed must be rejected with 422 Unprocessable Entity
	runReq.Seed = 999999999
	respTamper, _ := doJSONRequest(t, srv, http.MethodPost, "/api/v1/convoy/run/verify", playerID, runReq)
	if respTamper.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("expected 422 Unprocessable Entity on tampered seed, got %d", respTamper.StatusCode)
	}

	// 7. GET /api/v1/blueprints/carriages
	resp, body = doJSONRequest(t, srv, http.MethodGet, "/api/v1/blueprints/carriages", "", nil)
	if resp.StatusCode != http.StatusOK || !bytes.Contains(body, []byte("gatling_turret_car")) {
		t.Fatalf("GET /api/v1/blueprints/carriages failed: status=%d body=%s", resp.StatusCode, string(body))
	}

	// 8. GET /api/v1/release/status (unauthenticated and with player header)
	resp, body = doJSONRequest(t, srv, http.MethodGet, "/api/v1/release/status", "", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/v1/release/status expected 200, got %d: %s", resp.StatusCode, string(body))
	}
	var releasePayload map[string]any
	if err := json.Unmarshal(body, &releasePayload); err != nil {
		t.Fatalf("failed to unmarshal /api/v1/release/status JSON: %v", err)
	}
	if releasePayload["version"] != "1.0.0" || releasePayload["service"] != "chrono-convoy-backend" || releasePayload["status"] != "ok" {
		t.Fatalf("unexpected release status metadata: %+v", releasePayload)
	}
	dbMap, ok := releasePayload["database"].(map[string]any)
	if !ok || dbMap["connected"] != true || dbMap["driver"] != "postgres" {
		t.Fatalf("expected connected postgres database health in release status, got %+v", releasePayload["database"])
	}
	marketMap, ok := releasePayload["oasis_market"].(map[string]any)
	if !ok || marketMap["hydro_haven"] == nil || marketMap["iron_refinery"] == nil || marketMap["aether_spire"] == nil {
		t.Fatalf("expected all 3 Oasis Hubs in release status oasis_market, got %+v", releasePayload["oasis_market"])
	}
	if releasePayload["timestamp"] == nil || releasePayload["timestamp"] == "" {
		t.Fatalf("expected non-empty UTC ISO8601 timestamp in release status")
	}

	// Verify GET /api/v1/release/status also succeeds with X-Player-ID header
	respAuth, _ := doJSONRequest(t, srv, http.MethodGet, "/api/v1/release/status", "player_cmdr_riki", nil)
	if respAuth.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/v1/release/status with X-Player-ID expected 200, got %d", respAuth.StatusCode)
	}
}
