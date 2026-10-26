package qa

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync"
)

const (
	MaxReplayPayloadBytes   = 1 << 20 // 1 MiB strict payload ceiling
	MaxReplayTicks          = 216_000 // Max 1 hour at 60 Hz
	MaxReplayActions        = 10_000
	BaseMaxReactorWatts     = 100
	OverclockMaxReactorWatts = 130
	MaxPlausibleScrapPerRun = 25_000
	DefaultHMACSecret       = "chrono-convoy-sprint1-hmac-secret-key-2026"
)

// ReplayAction represents a deterministic player input or combat event at a fixed 60Hz tick.
type ReplayAction struct {
	Tick           int    `json:"tick"`
	ActionType     string `json:"action_type"` // REACTOR_ALLOC, TURRET_FIRE, TRACK_SWITCH, COOLING_ROD_VENT, RAIDER_KILL
	ShieldsWatts   int    `json:"shields_watts"`
	ArtilleryWatts int    `json:"artillery_watts"`
	ThrustersWatts int    `json:"thrusters_watts"`
	OverclockActive bool  `json:"overclock_active"`
	ScrapDelta     int64  `json:"scrap_delta"`
}

// ReplayVerifyRequest matches POST /api/v1/convoy/run/verify schema (BE-01 / BE-02).
type ReplayVerifyRequest struct {
	RunID               string         `json:"run_id"`
	IdempotencyKey      string         `json:"idempotency_key"`
	PlayerID            string         `json:"player_id"`
	ConvoyID            string         `json:"convoy_id"`
	OriginHubID         string         `json:"origin_hub_id"`
	DestinationHubID    string         `json:"destination_hub_id"`
	Seed                int64          `json:"seed"`
	TotalTicks          int            `json:"total_ticks"`
	ScrapEarned         int64          `json:"scrap_earned"`
	CoolingRodsConsumed int            `json:"cooling_rods_consumed"`
	DistanceTraveledM   float64        `json:"distance_traveled_m"`
	Actions             []ReplayAction `json:"actions"`
	FinalStateHash      string         `json:"final_state_hash"`
	SignatureScheme     string         `json:"signature_scheme"`
	Signature           string         `json:"signature"`
}

// ReplayVerifyResponse is returned by POST /api/v1/convoy/run/verify.
type ReplayVerifyResponse struct {
	Verified         bool   `json:"verified"`
	RunID            string `json:"run_id,omitempty"`
	IdempotencyKey   string `json:"idempotency_key,omitempty"`
	Duplicate        bool   `json:"duplicate"`
	LedgerCredited   bool   `json:"ledger_credited"`
	NewWalletBalance int64  `json:"new_wallet_balance,omitempty"`
	ServerStateHash  string `json:"server_state_hash,omitempty"`
	ErrorCode        string `json:"error_code,omitempty"`
	ErrorMessage     string `json:"error_message,omitempty"`
}

// ComputeDeterministicStateHash computes the canonical SHA-256 hash chain over the seed,
// route, tick actions, and terminal metrics.
func ComputeDeterministicStateHash(req *ReplayVerifyRequest) string {
	h := sha256.New()
	_, _ = fmt.Fprintf(h, "seed=%d|origin=%s|dest=%s|ticks=%d|rods=%d|dist=%.4f|",
		req.Seed, req.OriginHubID, req.DestinationHubID, req.TotalTicks, req.CoolingRodsConsumed, req.DistanceTraveledM)
	for i, a := range req.Actions {
		_, _ = fmt.Fprintf(h, "act[%d]=%d:%s:%d:%d:%d:%t:%d|",
			i, a.Tick, a.ActionType, a.ShieldsWatts, a.ArtilleryWatts, a.ThrustersWatts, a.OverclockActive, a.ScrapDelta)
	}
	_, _ = fmt.Fprintf(h, "scrap=%d", req.ScrapEarned)
	return hex.EncodeToString(h.Sum(nil))
}

// SignReplayPayload computes the canonical HMAC-SHA256 signature over the replay payload and state hash.
func SignReplayPayload(req *ReplayVerifyRequest, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	canonical := strings.Join([]string{
		req.RunID,
		req.IdempotencyKey,
		req.PlayerID,
		req.ConvoyID,
		req.OriginHubID,
		req.DestinationHubID,
		strconv.FormatInt(req.Seed, 10),
		strconv.Itoa(req.TotalTicks),
		strconv.FormatInt(req.ScrapEarned, 10),
		strconv.Itoa(req.CoolingRodsConsumed),
		fmt.Sprintf("%.4f", req.DistanceTraveledM),
		req.FinalStateHash,
	}, "|")
	_, _ = mac.Write([]byte(canonical))
	return hex.EncodeToString(mac.Sum(nil))
}

// ReplayVerifierEngine is a concurrency-safe deterministic replay verifier and ACID-like in-memory wallet ledger.
type ReplayVerifierEngine struct {
	mu               sync.Mutex
	hmacSecret       string
	verifiedRuns     map[string]ReplayVerifyResponse
	idempotencyIndex map[string]string
	walletBalances   map[string]int64
}

func NewReplayVerifierEngine(secret string) *ReplayVerifierEngine {
	if secret == "" {
		secret = DefaultHMACSecret
	}
	return &ReplayVerifierEngine{
		hmacSecret:       secret,
		verifiedRuns:     make(map[string]ReplayVerifyResponse),
		idempotencyIndex: make(map[string]string),
		walletBalances: map[string]int64{
			"player_cmdr_riki": 5000,
			"player_jack_test": 3500,
			"player_aegis_qa":  10000,
		},
	}
}

func (e *ReplayVerifierEngine) GetWalletBalance(playerID string) int64 {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.walletBalances[playerID]
}

func (e *ReplayVerifierEngine) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			ErrorCode:    "METHOD_NOT_ALLOWED",
			ErrorMessage: "Only POST is supported on /api/v1/convoy/run/verify",
		})
		return
	}

	// Enforce 1 MiB body size limit
	r.Body = http.MaxBytesReader(w, r.Body, MaxReplayPayloadBytes)
	bodyBytes, err := io.ReadAll(r.Body)
	if err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
				ErrorCode:    "PAYLOAD_TOO_LARGE",
				ErrorMessage: "Replay payload exceeds 1 MiB maximum size",
			})
			return
		}
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			ErrorCode:    "READ_BODY_ERROR",
			ErrorMessage: "Failed to read request body",
		})
		return
	}

	if len(bodyBytes) == 0 {
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			ErrorCode:    "EMPTY_PAYLOAD",
			ErrorMessage: "Request body cannot be empty",
		})
		return
	}

	var req ReplayVerifyRequest
	dec := json.NewDecoder(strings.NewReader(string(bodyBytes)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			ErrorCode:    "MALFORMED_JSON",
			ErrorMessage: "Invalid JSON syntax or schema: " + err.Error(),
		})
		return
	}

	// Validate structural and boundary invariants before locking ledger
	if code, msg := validateReplayInvariants(&req, e.hmacSecret); code != "" {
		status := http.StatusUnprocessableEntity
		if code == "INVALID_SIGNATURE" || code == "STATE_HASH_MISMATCH" {
			status = http.StatusUnprocessableEntity
		}
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			Verified:     false,
			RunID:        req.RunID,
			ErrorCode:    code,
			ErrorMessage: msg,
		})
		return
	}

	// Atomic idempotency check + wallet credit under mutex (serializable transaction equivalent)
	e.mu.Lock()
	if existingRunID, dupIdem := e.idempotencyIndex[req.IdempotencyKey]; dupIdem {
		e.mu.Unlock()
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			Verified:       false,
			RunID:          existingRunID,
			IdempotencyKey: req.IdempotencyKey,
			Duplicate:      true,
			LedgerCredited: false,
			ErrorCode:      "DUPLICATE_REPLAY_DETECTED",
			ErrorMessage:   "Replay idempotency_key has already been processed",
		})
		return
	}
	if _, dupRun := e.verifiedRuns[req.RunID]; dupRun {
		e.mu.Unlock()
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			Verified:       false,
			RunID:          req.RunID,
			IdempotencyKey: req.IdempotencyKey,
			Duplicate:      true,
			LedgerCredited: false,
			ErrorCode:      "DUPLICATE_RUN_ID",
			ErrorMessage:   "Run ID has already been verified and credited",
		})
		return
	}

	currentBal := e.walletBalances[req.PlayerID]
	if currentBal > math.MaxInt64-req.ScrapEarned {
		e.mu.Unlock()
		w.WriteHeader(http.StatusUnprocessableEntity)
		_ = json.NewEncoder(w).Encode(ReplayVerifyResponse{
			ErrorCode:    "WALLET_OVERFLOW",
			ErrorMessage: "Crediting scrap would overflow int64 wallet balance",
		})
		return
	}

	newBal := currentBal + req.ScrapEarned
	e.walletBalances[req.PlayerID] = newBal
	resp := ReplayVerifyResponse{
		Verified:         true,
		RunID:            req.RunID,
		IdempotencyKey:   req.IdempotencyKey,
		Duplicate:        false,
		LedgerCredited:   true,
		NewWalletBalance: newBal,
		ServerStateHash:  req.FinalStateHash,
	}
	e.verifiedRuns[req.RunID] = resp
	e.idempotencyIndex[req.IdempotencyKey] = req.RunID
	e.mu.Unlock()

	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(resp)
}

func validateReplayInvariants(req *ReplayVerifyRequest, secret string) (string, string) {
	if strings.TrimSpace(req.RunID) == "" || strings.TrimSpace(req.IdempotencyKey) == "" ||
		strings.TrimSpace(req.PlayerID) == "" || strings.TrimSpace(req.ConvoyID) == "" ||
		strings.TrimSpace(req.OriginHubID) == "" || strings.TrimSpace(req.DestinationHubID) == "" {
		return "MISSING_REQUIRED_IDENTIFIERS", "run_id, idempotency_key, player_id, convoy_id, origin_hub_id, and destination_hub_id are required"
	}
	if req.OriginHubID == req.DestinationHubID {
		return "INVALID_ROUTE", "origin_hub_id and destination_hub_id must be distinct Oasis hubs"
	}
	if req.TotalTicks <= 0 || req.TotalTicks > MaxReplayTicks {
		return "INVALID_TICK_BOUNDS", fmt.Sprintf("total_ticks (%d) must be in 1..%d", req.TotalTicks, MaxReplayTicks)
	}
	if len(req.Actions) == 0 || len(req.Actions) > MaxReplayActions {
		return "INVALID_ACTION_COUNT", fmt.Sprintf("actions length (%d) must be in 1..%d", len(req.Actions), MaxReplayActions)
	}
	if req.ScrapEarned < 0 || req.ScrapEarned > MaxPlausibleScrapPerRun {
		return "INVALID_SCRAP_DELTA", fmt.Sprintf("scrap_earned (%d) out of plausible bounds [0..%d]", req.ScrapEarned, MaxPlausibleScrapPerRun)
	}
	if req.CoolingRodsConsumed < 0 || req.CoolingRodsConsumed > 100 {
		return "INVALID_COOLING_RODS", "cooling_rods_consumed must be in [0..100]"
	}
	if math.IsNaN(req.DistanceTraveledM) || math.IsInf(req.DistanceTraveledM, 0) || req.DistanceTraveledM <= 0 || req.DistanceTraveledM > 100_000 {
		return "INVALID_DISTANCE", "distance_traveled_m must be finite and in (0..100000]"
	}
	if req.SignatureScheme != "HMAC-SHA256" && req.SignatureScheme != "AES-256-GCM" {
		return "UNSUPPORTED_SIGNATURE_SCHEME", "signature_scheme must be HMAC-SHA256 or AES-256-GCM"
	}

	var prevTick = -1
	var accumulatedActionScrap int64
	var overclockEvents int

	for i, act := range req.Actions {
		if act.Tick < 0 || act.Tick > req.TotalTicks {
			return "TICK_OUT_OF_RANGE", fmt.Sprintf("action[%d] tick %d outside [0..%d]", i, act.Tick, req.TotalTicks)
		}
		if act.Tick < prevTick {
			return "NON_MONOTONIC_TICK", fmt.Sprintf("action[%d] tick %d regressed before previous tick %d", i, act.Tick, prevTick)
		}
		prevTick = act.Tick

		if act.ShieldsWatts < 0 || act.ArtilleryWatts < 0 || act.ThrustersWatts < 0 {
			return "NEGATIVE_REACTOR_WATTS", fmt.Sprintf("action[%d] has negative subsystem watt allocation", i)
		}
		if act.ShieldsWatts > 60 || act.ArtilleryWatts > 60 || act.ThrustersWatts > 60 {
			return "SUBSYSTEM_CAP_EXCEEDED", fmt.Sprintf("action[%d] subsystem watt exceeds 60W ceiling", i)
		}
		wattSum := act.ShieldsWatts + act.ArtilleryWatts + act.ThrustersWatts
		maxAllowed := BaseMaxReactorWatts
		if act.OverclockActive {
			maxAllowed = OverclockMaxReactorWatts
			overclockEvents++
		}
		if wattSum > maxAllowed {
			return "REACTOR_BUDGET_EXCEEDED", fmt.Sprintf("action[%d] total watts %dW > max %dW", i, wattSum, maxAllowed)
		}

		if act.ScrapDelta < 0 || act.ScrapDelta > 5_000 {
			return "INVALID_ACTION_SCRAP_DELTA", fmt.Sprintf("action[%d] invalid scrap_delta %d", i, act.ScrapDelta)
		}
		if accumulatedActionScrap > math.MaxInt64-act.ScrapDelta {
			return "SCRAP_ACCUMULATION_OVERFLOW", "action scrap accumulation overflowed int64"
		}
		accumulatedActionScrap += act.ScrapDelta
	}

	if overclockEvents > 0 && req.CoolingRodsConsumed <= 0 {
		return "UNPAID_OVERCLOCK_COOLING_ROD_SINK", "overclocked reactor ticks require cooling_rods_consumed >= 1"
	}
	if accumulatedActionScrap != req.ScrapEarned {
		return "SCRAP_LEDGER_MISMATCH", fmt.Sprintf("sum of action scrap_delta (%d) != declared scrap_earned (%d)", accumulatedActionScrap, req.ScrapEarned)
	}

	expectedStateHash := ComputeDeterministicStateHash(req)
	if !hmac.Equal([]byte(strings.ToLower(req.FinalStateHash)), []byte(expectedStateHash)) {
		return "STATE_HASH_MISMATCH", "client final_state_hash does not match deterministic server replay state hash"
	}

	expectedSig := SignReplayPayload(req, secret)
	if !hmac.Equal([]byte(strings.ToLower(req.Signature)), []byte(expectedSig)) {
		return "INVALID_SIGNATURE", "HMAC-SHA256 replay signature verification failed"
	}

	return "", ""
}

// NewReplayVerifyMux creates an HTTP ServeMux exposing POST /api/v1/convoy/run/verify.
func NewReplayVerifyMux(engine *ReplayVerifierEngine) *http.ServeMux {
	mux := http.NewServeMux()
	mux.Handle("/api/v1/convoy/run/verify", engine)
	return mux
}
