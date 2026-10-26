package qa

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"
)

const (
	liveBackendBaseURL = "http://127.0.0.1:8080"
	liveMasterSecret   = "chrono-convoy-iron-drift-sprint1-master-secret-2026"
	liveActiveKeyID    = "chrono-key-2026-v1"
)

func ensureLiveBackendRunning(t *testing.T) {
	t.Helper()
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(liveBackendBaseURL + "/healthz")
	if err == nil {
		_ = resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			return
		}
	}

	// Build and start Patrick's live backend if not already listening on 127.0.0.1:8080
	buildCmd := exec.Command("go", "build", "-o", "/tmp/chrono-convoy-server", "./cmd/server")
	buildCmd.Dir = "/workspace/chrono-convoy-iron-drift/backend"
	if out, err := buildCmd.CombinedOutput(); err != nil {
		t.Fatalf("failed to build backend server: %v (%s)", err, string(out))
	}

	srvCmd := exec.Command("/tmp/chrono-convoy-server")
	srvCmd.Dir = "/workspace/chrono-convoy-iron-drift/backend"
	if err := srvCmd.Start(); err != nil {
		t.Fatalf("failed to start backend server: %v", err)
	}

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		r, err := client.Get(liveBackendBaseURL + "/healthz")
		if err == nil {
			_ = r.Body.Close()
			if r.StatusCode == http.StatusOK {
				return
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("backend at %s/healthz did not become ready within timeout", liveBackendBaseURL)
}

func doLiveHTTPJSON(t *testing.T, method, path, playerID string, reqBody any) (*http.Response, []byte) {
	t.Helper()
	var bodyReader io.Reader
	if reqBody != nil {
		raw, err := json.Marshal(reqBody)
		if err != nil {
			t.Fatalf("json.Marshal request failed: %v", err)
		}
		bodyReader = bytes.NewReader(raw)
	}

	req, err := http.NewRequest(method, liveBackendBaseURL+path, bodyReader)
	if err != nil {
		t.Fatalf("http.NewRequest failed: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if playerID != "" {
		req.Header.Set("X-Player-ID", playerID)
	}

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("HTTP %s %s failed: %v", method, path, err)
	}
	respBytes, err := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if err != nil {
		t.Fatalf("reading response body failed: %v", err)
	}
	return resp, respBytes
}

func queryPostgresScalar(t *testing.T, sqlQuery string) string {
	t.Helper()
	cmd := exec.Command("psql", "-U", "box", "-d", "chrono_convoy", "-t", "-A", "-c", sqlQuery)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("psql query failed (%s): %v — output: %s", sqlQuery, err, string(out))
	}
	return strings.TrimSpace(string(out))
}

func signBackendRequestHMAC(t *testing.T, req *BackendVerifyRunRequest) string {
	t.Helper()
	key := sha256.Sum256([]byte(liveMasterSecret))
	payload, err := canonicalBackendPayload(req)
	if err != nil {
		t.Fatalf("canonicalBackendPayload failed: %v", err)
	}
	mac := hmac.New(sha256.New, key[:])
	mac.Write(payload)
	return hex.EncodeToString(mac.Sum(nil))
}

func sealBackendRequestAEAD(t *testing.T, req *BackendVerifyRunRequest) (string, string) {
	t.Helper()
	key := sha256.Sum256([]byte(liveMasterSecret))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		t.Fatalf("aes.NewCipher failed: %v", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatalf("cipher.NewGCM failed: %v", err)
	}
	nonce := make([]byte, gcm.NonceSize())
	binary.BigEndian.PutUint64(nonce[4:], uint64(req.Seed))
	payload, err := canonicalBackendPayload(req)
	if err != nil {
		t.Fatalf("canonicalBackendPayload failed: %v", err)
	}
	aad := []byte(fmt.Sprintf("v1|%s|%s|%s|%d", req.RunID, req.PlayerID, req.ConvoyID, req.Seed))
	sealed := gcm.Seal(nil, nonce, payload, aad)
	return hex.EncodeToString(nonce), hex.EncodeToString(sealed)
}

func TestLiveRelease_1_HealthAndLoadoutABAC(t *testing.T) {
	ensureLiveBackendRunning(t)

	// 1. Verify GET /healthz & OWASP 2025 security headers
	resp, body := doLiveHTTPJSON(t, http.MethodGet, "/healthz", "", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /healthz expected 200, got %d: %s", resp.StatusCode, string(body))
	}
	if resp.Header.Get("X-Content-Type-Options") != "nosniff" {
		t.Errorf("expected X-Content-Type-Options: nosniff header, got %q", resp.Header.Get("X-Content-Type-Options"))
	}
	if resp.Header.Get("X-Frame-Options") != "DENY" {
		t.Errorf("expected X-Frame-Options: DENY header, got %q", resp.Header.Get("X-Frame-Options"))
	}

	var health map[string]any
	if err := json.Unmarshal(body, &health); err != nil {
		t.Fatalf("unmarshal /healthz failed: %v", err)
	}
	if health["status"] != "ok" {
		t.Fatalf("expected health status 'ok', got %v", health["status"])
	}

	// 2. Fetch existing seeded convoy loadout (convoy_iron_drift_01 owned by player_cmdr_riki)
	resp, body = doLiveHTTPJSON(t, http.MethodGet, "/api/v1/convoy/loadout/convoy_iron_drift_01", "player_cmdr_riki", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/v1/convoy/loadout/convoy_iron_drift_01 expected 200, got %d: %s", resp.StatusCode, string(body))
	}

	// 3. Verify ABAC / Anti-IDOR blocks unauthorized player access with 403 Forbidden
	respIDOR, _ := doLiveHTTPJSON(t, http.MethodGet, "/api/v1/convoy/loadout/convoy_iron_drift_01", "player_unauthorized_raider", nil)
	if respIDOR.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden on cross-player IDOR attempt, got %d", respIDOR.StatusCode)
	}

	// 4. Create a fresh v1.0.0 release test convoy loadout and verify in PostgreSQL chrono_convoy
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	playerID := "player_rel_v1_" + suffix
	convoyID := "convoy_rel_v1_" + suffix

	createLoadoutReq := map[string]any{
		"convoy_id":          convoyID,
		"player_id":          playerID,
		"locomotive_class":   "iron_drifter_mk1",
		"max_reactor_watts":  100,
		"max_cargo_capacity": 240,
		"cooling_rod_bay":    12,
		"carriages": []map[string]any{
			{"slot": 1, "blueprint_id": "gatling_turret_car", "watt_draw": 10, "cargo_cap": 40},
			{"slot": 2, "blueprint_id": "cargo_smelter_car", "watt_draw": 10, "cargo_cap": 200},
		},
	}
	respCreate, bodyCreate := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/convoy/loadout", playerID, createLoadoutReq)
	if respCreate.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/convoy/loadout expected 200, got %d: %s", respCreate.StatusCode, string(bodyCreate))
	}

	dbWatts := queryPostgresScalar(t, fmt.Sprintf("SELECT max_reactor_watts FROM convoy_loadouts WHERE convoy_id = '%s';", convoyID))
	if dbWatts != "100" {
		t.Fatalf("PostgreSQL convoy_loadouts expected max_reactor_watts=100, got %q", dbWatts)
	}
	t.Logf("[PASS] Live GET/POST /api/v1/convoy/loadout + ABAC Anti-IDOR + PostgreSQL convoy_loadouts persistence verified")
}

func TestLiveRelease_2_60HzCombatSimulationAndSignedReplayPersistence(t *testing.T) {
	ensureLiveBackendRunning(t)

	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	playerID := "player_sim_v1_" + suffix
	convoyID := "convoy_sim_v1_" + suffix

	// Provision convoy & initial cargo (purified_water) so DELIVER_CARGO settles cleanly
	createLoadoutReq := map[string]any{
		"convoy_id":          convoyID,
		"player_id":          playerID,
		"locomotive_class":   "iron_drifter_mk1",
		"max_reactor_watts":  100,
		"max_cargo_capacity": 240,
		"cooling_rod_bay":    12,
		"carriages": []map[string]any{
			{"slot": 1, "blueprint_id": "gatling_turret_car", "watt_draw": 10, "cargo_cap": 40},
			{"slot": 2, "blueprint_id": "cargo_smelter_car", "watt_draw": 10, "cargo_cap": 200},
		},
	}
	resp, body := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/convoy/loadout", playerID, createLoadoutReq)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/convoy/loadout failed: %d %s", resp.StatusCode, string(body))
	}

	// Buy 15 units of purified_water at hydro_haven prior to departure
	buyReq := map[string]any{
		"idempotency_key": "idem_pre_run_buy_" + suffix,
		"player_id":       playerID,
		"convoy_id":       convoyID,
		"hub_id":          "hydro_haven",
		"commodity_id":    "purified_water",
		"action":          "BUY",
		"quantity":        15,
	}
	respBuy, bodyBuy := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/oasis/market/trade", playerID, buyReq)
	if respBuy.StatusCode != http.StatusOK {
		t.Fatalf("pre-run BUY failed: %d %s", respBuy.StatusCode, string(bodyBuy))
	}

	walletBeforeRunStr := queryPostgresScalar(t, fmt.Sprintf("SELECT iron_scrip FROM player_wallets WHERE player_id = '%s';", playerID))
	walletBeforeRun, err := strconv.ParseInt(walletBeforeRunStr, 10, 64)
	if err != nil {
		t.Fatalf("failed to parse walletBeforeRun: %v", err)
	}

	// Step 2: Simulate 60Hz deterministic convoy combat run & build signed HMAC-SHA256 replay
	localSimReq := buildValidSignedReplay("run_sim_local", "idem_sim_local", playerID, 777888999, 320)
	localStateHash := ComputeDeterministicStateHash(&localSimReq)
	if len(localStateHash) != 64 {
		t.Fatalf("local 60Hz combat simulation hash failed")
	}

	runID := "run_hmac_v1_" + suffix
	hmacReq := &BackendVerifyRunRequest{
		RunID:              runID,
		IdempotencyKey:     "idem_run_hmac_" + suffix,
		PlayerID:           playerID,
		ConvoyID:           convoyID,
		OriginHubID:        "hydro_haven",
		DestinationHubID:   "iron_refinery",
		Seed:               777888999,
		MaxReactorWatts:    100,
		InitialCoolingRods: 10,
		KeyID:              liveActiveKeyID,
		Actions: []BackendAction{
			{Tick: 0, Type: "ALLOCATE_WATTS", ShieldsWatts: 30, ArtilleryWatts: 35, ThrustersWatts: 35},
			{Tick: 120, Type: "OVERCLOCK_ROD"},
			{Tick: 125, Type: "ALLOCATE_WATTS", ShieldsWatts: 40, ArtilleryWatts: 45, ThrustersWatts: 45},
			{Tick: 180, Type: "SWITCH_BRANCH", BranchID: "canyon_high_ground"},
			{Tick: 240, Type: "FIRE_TURRET", TargetRaiderID: "raider_01"},
			{Tick: 320, Type: "RAM_RAIDER", TargetRaiderID: "raider_02"},
			{Tick: 420, Type: "FLUSH_COOLING_ROD"},
			{Tick: 600, Type: "DELIVER_CARGO", CommodityID: "purified_water", Quantity: 10},
		},
	}
	hmacReq.SignatureHMAC = signBackendRequestHMAC(t, hmacReq)

	// Step 3A: Submit signed HMAC-SHA256 replay to POST /api/v1/convoy/run/verify
	respVerify, bodyVerify := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/convoy/run/verify", playerID, hmacReq)
	if respVerify.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/convoy/run/verify expected 200, got %d: %s", respVerify.StatusCode, string(bodyVerify))
	}

	var verifyResp struct {
		Verification struct {
			Verified            bool   `json:"verified"`
			RunID               string `json:"run_id"`
			ScrapEarned         int64  `json:"scrap_earned"`
			CoolingRodsConsumed int    `json:"cooling_rods_consumed"`
			FinalStateHash      string `json:"final_state_hash"`
			SignatureScheme     string `json:"signature_scheme"`
		} `json:"verification"`
		LedgerEntry struct {
			EntryID           int64  `json:"entry_id"`
			EntryType         string `json:"entry_type"`
			ScripDelta        int64  `json:"scrip_delta"`
			ScripBalanceAfter int64  `json:"scrip_balance_after"`
			IdempotentReplay  bool   `json:"idempotent_replay"`
		} `json:"ledger_entry"`
	}
	if err := json.Unmarshal(bodyVerify, &verifyResp); err != nil {
		t.Fatalf("unmarshal verifyResp failed: %v", err)
	}
	if !verifyResp.Verification.Verified || verifyResp.Verification.SignatureScheme != "HMAC-SHA256" {
		t.Fatalf("unexpected verification result: %+v", verifyResp.Verification)
	}
	if len(verifyResp.Verification.FinalStateHash) != 64 {
		t.Fatalf("expected 64-char SHA-256 final_state_hash, got %q", verifyResp.Verification.FinalStateHash)
	}

	// Verify idempotent replay on duplicate submission to alias route POST /api/v1/run/verify
	respDup, bodyDup := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/run/verify", playerID, hmacReq)
	if respDup.StatusCode != http.StatusOK || !bytes.Contains(bodyDup, []byte(`"idempotent_replay":true`)) {
		t.Fatalf("expected 200 idempotent_replay:true on duplicate run verify, got %d: %s", respDup.StatusCode, string(bodyDup))
	}

	// Step 3B: Submit AES-256-GCM AEAD encrypted & authenticated replay
	aeadRunID := "run_aead_v1_" + suffix
	aeadReq := &BackendVerifyRunRequest{
		RunID:              aeadRunID,
		IdempotencyKey:     "idem_run_aead_" + suffix,
		PlayerID:           playerID,
		ConvoyID:           convoyID,
		OriginHubID:        "iron_refinery",
		DestinationHubID:   "aether_spire",
		Seed:               888999111,
		MaxReactorWatts:    100,
		InitialCoolingRods: 10,
		KeyID:              liveActiveKeyID,
		Actions: []BackendAction{
			{Tick: 0, Type: "ALLOCATE_WATTS", ShieldsWatts: 35, ArtilleryWatts: 30, ThrustersWatts: 35},
			{Tick: 200, Type: "FIRE_TURRET", TargetRaiderID: "raider_03"},
			{Tick: 400, Type: "FLUSH_COOLING_ROD"},
		},
	}
	aeadReq.AEADNonceHex, aeadReq.AEADCipherHex = sealBackendRequestAEAD(t, aeadReq)

	respAEAD, bodyAEAD := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/convoy/run/verify", playerID, aeadReq)
	if respAEAD.StatusCode != http.StatusOK || !bytes.Contains(bodyAEAD, []byte(`"AES-256-GCM"`)) {
		t.Fatalf("POST /api/v1/convoy/run/verify (AEAD) expected 200 AES-256-GCM, got %d: %s", respAEAD.StatusCode, string(bodyAEAD))
	}

	// Verify PostgreSQL persistence in verified_convoy_runs and player_wallets
	dbRunRow := queryPostgresScalar(t, fmt.Sprintf(
		"SELECT signature_scheme || '|' || final_state_hash || '|' || scrap_earned FROM verified_convoy_runs WHERE run_id = '%s';",
		runID,
	))
	expectedPrefix := fmt.Sprintf("HMAC-SHA256|%s|%d", verifyResp.Verification.FinalStateHash, verifyResp.Verification.ScrapEarned)
	if dbRunRow != expectedPrefix {
		t.Fatalf("PostgreSQL verified_convoy_runs row mismatch: got %q, want %q", dbRunRow, expectedPrefix)
	}

	dbAEADScheme := queryPostgresScalar(t, fmt.Sprintf(
		"SELECT signature_scheme FROM verified_convoy_runs WHERE run_id = '%s';",
		aeadRunID,
	))
	if dbAEADScheme != "AES-256-GCM" {
		t.Fatalf("PostgreSQL verified_convoy_runs expected AES-256-GCM for %s, got %q", aeadRunID, dbAEADScheme)
	}

	walletAfterRunsStr := queryPostgresScalar(t, fmt.Sprintf("SELECT iron_scrip FROM player_wallets WHERE player_id = '%s';", playerID))
	walletAfterRuns, _ := strconv.ParseInt(walletAfterRunsStr, 10, 64)
	if walletAfterRuns <= walletBeforeRun {
		t.Fatalf("expected player wallet iron_scrip to increase after verified convoy runs: before=%d after=%d", walletBeforeRun, walletAfterRuns)
	}

	t.Logf("[PASS] Live 60Hz Replay Verification (HMAC-SHA256 + AES-256-GCM AEAD) persisted in PostgreSQL verified_convoy_runs (state_hash=%s, wallet: %d -> %d)",
		verifyResp.Verification.FinalStateHash[:16], walletBeforeRun, walletAfterRuns)
}

func TestLiveRelease_3_OasisHubACIDCommodityTradeAndConservation(t *testing.T) {
	ensureLiveBackendRunning(t)

	// 1. Verify GET /api/v1/oasis/market returns all 3 Oasis Hubs
	respMkt, bodyMkt := doLiveHTTPJSON(t, http.MethodGet, "/api/v1/oasis/market", "", nil)
	if respMkt.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/v1/oasis/market expected 200, got %d: %s", respMkt.StatusCode, string(bodyMkt))
	}
	for _, hub := range []string{"hydro_haven", "iron_refinery", "aether_spire"} {
		if !bytes.Contains(bodyMkt, []byte(hub)) {
			t.Fatalf("GET /api/v1/oasis/market missing hub %q", hub)
		}
	}

	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	playerID := "player_trade_v1_" + suffix
	convoyID := "convoy_trade_v1_" + suffix

	// 2. Provision convoy for ACID trade verification
	createLoadoutReq := map[string]any{
		"convoy_id":          convoyID,
		"player_id":          playerID,
		"locomotive_class":   "iron_drifter_mk1",
		"max_reactor_watts":  100,
		"max_cargo_capacity": 240,
		"cooling_rod_bay":    12,
		"carriages": []map[string]any{
			{"slot": 1, "blueprint_id": "cargo_smelter_car", "watt_draw": 10, "cargo_cap": 240},
		},
	}
	resp, body := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/convoy/loadout", playerID, createLoadoutReq)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/convoy/loadout failed: %d %s", resp.StatusCode, string(body))
	}

	initialScripStr := queryPostgresScalar(t, fmt.Sprintf("SELECT iron_scrip FROM player_wallets WHERE player_id = '%s';", playerID))
	initialScrip, _ := strconv.ParseInt(initialScripStr, 10, 64)

	// 3. Execute ACID BUY of 20 purified_water at hydro_haven
	buyIdemKey := "idem_acid_buy_" + suffix
	buyReq := map[string]any{
		"idempotency_key": buyIdemKey,
		"player_id":       playerID,
		"convoy_id":       convoyID,
		"hub_id":          "hydro_haven",
		"commodity_id":    "purified_water",
		"action":          "BUY",
		"quantity":        20,
	}
	respBuy, bodyBuy := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/oasis/market/trade", playerID, buyReq)
	if respBuy.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/oasis/market/trade (BUY) expected 200, got %d: %s", respBuy.StatusCode, string(bodyBuy))
	}

	// Verify duplicate BUY with same idempotency_key returns idempotent_replay: true without double-charging
	respBuyDup, bodyBuyDup := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/oasis/market/trade", playerID, buyReq)
	if respBuyDup.StatusCode != http.StatusOK || !bytes.Contains(bodyBuyDup, []byte(`"idempotent_replay":true`)) {
		t.Fatalf("duplicate BUY expected idempotent_replay:true, got %d: %s", respBuyDup.StatusCode, string(bodyBuyDup))
	}

	// 4. Execute ACID DELIVER of 12 purified_water at iron_refinery (leaves 8 in cargo hold)
	deliverIdemKey := "idem_acid_deliver_" + suffix
	deliverReq := map[string]any{
		"idempotency_key": deliverIdemKey,
		"player_id":       playerID,
		"convoy_id":       convoyID,
		"hub_id":          "iron_refinery",
		"commodity_id":    "purified_water",
		"action":          "DELIVER",
		"quantity":        12,
	}
	respDel, bodyDel := doLiveHTTPJSON(t, http.MethodPost, "/api/v1/oasis/market/trade", playerID, deliverReq)
	if respDel.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/v1/oasis/market/trade (DELIVER) expected 200, got %d: %s", respDel.StatusCode, string(bodyDel))
	}

	// 5. Verify PostgreSQL ACID conservation across player_wallets, convoy_cargo_inventory, and commodity_ledger_entries
	cargoQtyStr := queryPostgresScalar(t, fmt.Sprintf(
		"SELECT quantity FROM convoy_cargo_inventory WHERE convoy_id = '%s' AND commodity_id = 'purified_water';",
		convoyID,
	))
	if cargoQtyStr != "8" {
		t.Fatalf("PostgreSQL convoy_cargo_inventory expected 8 remaining purified_water (20 bought - 12 delivered), got %q", cargoQtyStr)
	}

	ledgerSumStr := queryPostgresScalar(t, fmt.Sprintf(
		"SELECT COALESCE(SUM(scrip_delta), 0) FROM commodity_ledger_entries WHERE player_id = '%s';",
		playerID,
	))
	ledgerSum, _ := strconv.ParseInt(ledgerSumStr, 10, 64)

	finalScripStr := queryPostgresScalar(t, fmt.Sprintf("SELECT iron_scrip FROM player_wallets WHERE player_id = '%s';", playerID))
	finalScrip, _ := strconv.ParseInt(finalScripStr, 10, 64)

	if initialScrip+ledgerSum != finalScrip {
		t.Fatalf("ACID wallet conservation violated: initial(%d) + ledgerSum(%d) != final(%d)", initialScrip, ledgerSum, finalScrip)
	}

	t.Logf("[PASS] Live Oasis Hub ACID Trade & PostgreSQL Conservation verified (initial=%d, net_delta=%d, final=%d, cargo_remaining=%s)",
		initialScrip, ledgerSum, finalScrip, cargoQtyStr)
}

func TestLiveRelease_4_BlueprintsAndNoSQLInjectionDefense(t *testing.T) {
	ensureLiveBackendRunning(t)

	resp, body := doLiveHTTPJSON(t, http.MethodGet, "/api/v1/blueprints/carriages", "", nil)
	if resp.StatusCode != http.StatusOK || !bytes.Contains(body, []byte("gatling_turret_car")) {
		t.Fatalf("GET /api/v1/blueprints/carriages expected 200 with gatling_turret_car, got %d: %s", resp.StatusCode, string(body))
	}

	// Verify NoSQL operator injection attempt ($ne) is rejected with 400 Bad Request
	respInj, _ := doLiveHTTPJSON(t, http.MethodGet, "/api/v1/blueprints/carriages?slot_category=$ne", "", nil)
	if respInj.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request on NoSQL operator injection attempt, got %d", respInj.StatusCode)
	}
	t.Logf("[PASS] Live Blueprint Bucket Document Store & OWASP NoSQL Injection defense verified")
}
