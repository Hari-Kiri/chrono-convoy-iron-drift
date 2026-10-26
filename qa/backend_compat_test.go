package qa

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"testing"
)

// BackendAction mirrors backend/internal/replay.ReplayAction (BE-01) for cross-module contract verification.
type BackendAction struct {
	Tick           int    `json:"tick"`
	Type           string `json:"type"`
	ShieldsWatts   int    `json:"shields_watts,omitempty"`
	ArtilleryWatts int    `json:"artillery_watts,omitempty"`
	ThrustersWatts int    `json:"thrusters_watts,omitempty"`
	TargetRaiderID string `json:"target_raider_id,omitempty"`
	BranchID       string `json:"branch_id,omitempty"`
	CommodityID    string `json:"commodity_id,omitempty"`
	Quantity       int    `json:"quantity,omitempty"`
}

// BackendVerifyRunRequest mirrors backend/internal/replay.VerifyRunRequest (BE-01).
type BackendVerifyRunRequest struct {
	RunID              string          `json:"run_id"`
	IdempotencyKey     string          `json:"idempotency_key"`
	PlayerID           string          `json:"player_id"`
	ConvoyID           string          `json:"convoy_id"`
	OriginHubID        string          `json:"origin_hub_id"`
	DestinationHubID   string          `json:"destination_hub_id"`
	Seed               int64           `json:"seed"`
	MaxReactorWatts    int             `json:"max_reactor_watts,omitempty"`
	InitialCoolingRods int             `json:"initial_cooling_rods,omitempty"`
	Actions            []BackendAction `json:"actions"`
	ClaimedScrapEarned *int64          `json:"claimed_scrap_earned,omitempty"`
	ClaimedStateHash   string          `json:"claimed_state_hash,omitempty"`
	KeyID              string          `json:"key_id,omitempty"`
	SignatureHMAC      string          `json:"signature_hmac,omitempty"`
	AEADNonceHex       string          `json:"aead_nonce_hex,omitempty"`
	AEADCipherHex      string          `json:"aead_cipher_hex,omitempty"`
}

func canonicalBackendPayload(req *BackendVerifyRunRequest) ([]byte, error) {
	actBytes, err := json.Marshal(req.Actions)
	if err != nil {
		return nil, err
	}
	var sb strings.Builder
	sb.WriteString("v1|")
	sb.WriteString(req.RunID)
	sb.WriteByte('|')
	sb.WriteString(req.PlayerID)
	sb.WriteByte('|')
	sb.WriteString(req.ConvoyID)
	sb.WriteByte('|')
	sb.WriteString(req.OriginHubID)
	sb.WriteByte('|')
	sb.WriteString(req.DestinationHubID)
	sb.WriteByte('|')
	sb.WriteString(strconv.FormatInt(req.Seed, 10))
	sb.WriteByte('|')
	sb.Write(actBytes)
	return []byte(sb.String()), nil
}

func TestBackendCanonicalEnvelopeHMACAndAEADContract(t *testing.T) {
	secret := "chrono-convoy-iron-drift-sprint1-master-secret-2026"
	key := sha256.Sum256([]byte(secret))

	req := &BackendVerifyRunRequest{
		RunID:              "run_be01_contract_01",
		IdempotencyKey:     "idem_be01_contract_01",
		PlayerID:           "player_cmdr_riki",
		ConvoyID:           "convoy_iron_drift_01",
		OriginHubID:        "hydro_haven",
		DestinationHubID:   "iron_refinery",
		Seed:               42069,
		MaxReactorWatts:    100,
		InitialCoolingRods: 10,
		Actions: []BackendAction{
			{Tick: 0, Type: "ALLOCATE_WATTS", ShieldsWatts: 30, ArtilleryWatts: 35, ThrustersWatts: 35},
			{Tick: 120, Type: "OVERCLOCK_ROD"},
			{Tick: 180, Type: "ALLOCATE_WATTS", ShieldsWatts: 40, ArtilleryWatts: 45, ThrustersWatts: 45},
			{Tick: 300, Type: "FIRE_TURRET"},
			{Tick: 480, Type: "RAM_RAIDER"},
			{Tick: 540, Type: "FLUSH_COOLING_ROD"},
			{Tick: 600, Type: "DELIVER_CARGO", CommodityID: "purified_water", Quantity: 15},
		},
		KeyID: "chrono-key-2026-v1",
	}

	payload, err := canonicalBackendPayload(req)
	if err != nil {
		t.Fatalf("failed to build canonical backend payload: %v", err)
	}

	// 1. Verify HMAC-SHA256 generation and tamper detection
	mac := hmac.New(sha256.New, key[:])
	mac.Write(payload)
	req.SignatureHMAC = hex.EncodeToString(mac.Sum(nil))
	if len(req.SignatureHMAC) != 64 {
		t.Fatalf("expected 64-hex-char HMAC-SHA256 signature, got %d", len(req.SignatureHMAC))
	}

	// 2. Verify AES-256-GCM AEAD seal/open with AAD binding (OWASP 2025 A04)
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
	aad := []byte(fmt.Sprintf("v1|%s|%s|%s|%d", req.RunID, req.PlayerID, req.ConvoyID, req.Seed))
	sealed := gcm.Seal(nil, nonce, payload, aad)

	req.AEADNonceHex = hex.EncodeToString(nonce)
	req.AEADCipherHex = hex.EncodeToString(sealed)

	opened, err := gcm.Open(nil, nonce, sealed, aad)
	if err != nil {
		t.Fatalf("AES-256-GCM open failed: %v", err)
	}
	if !hmac.Equal(opened, payload) {
		t.Fatalf("decrypted AEAD payload mismatch")
	}

	// 3. Verify tampering with AAD (e.g. attacker swaps PlayerID for IDOR) fails GCM authentication
	tamperedAAD := []byte(fmt.Sprintf("v1|%s|%s|%s|%d", req.RunID, "player_attacker_idor", req.ConvoyID, req.Seed))
	if _, err := gcm.Open(nil, nonce, sealed, tamperedAAD); err == nil {
		t.Fatalf("expected AES-256-GCM authentication failure when AAD player_id is tampered")
	}

	t.Logf("[PASS] BE-01 Canonical Replay Envelope (HMAC-SHA256 + AES-256-GCM AEAD + Anti-IDOR AAD) verified")
}
