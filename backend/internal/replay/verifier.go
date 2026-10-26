package replay

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
)

var (
	ErrForbiddenIDOR                = errors.New("abac authorization failed: caller does not own target player_id or convoy_id")
	ErrCryptoVerificationFailed     = errors.New("cryptographic signature or AEAD authentication tag verification failed")
	ErrInvalidReplayPayload         = errors.New("invalid or malformed replay verification payload")
	ErrNonMonotonicTick             = errors.New("replay action log contains non-monotonic or out-of-range tick")
	ErrWattBudgetExceeded           = errors.New("reactor power-routing watt conservation violated")
	ErrThermalMeltdownOrRodShortage = errors.New("reactor thermal shutdown exceeded or insufficient cooling rods")
	ErrTamperedReplayState          = errors.New("deterministic replay output diverges from client-claimed state or rewards")
)

const (
	DefaultMaxReactorWatts = 100
	OverclockBonusWatts    = 30
	SubsystemMaxWatts      = 60
	MaxAllowedTicks        = 36000 // 10 minutes at 60Hz
)

// ActionType enumerates deterministic convoy actions recorded during a run.
type ActionType string

const (
	ActionAllocateWatts   ActionType = "ALLOCATE_WATTS"
	ActionFireTurret      ActionType = "FIRE_TURRET"
	ActionRamRaider       ActionType = "RAM_RAIDER"
	ActionFlushCoolingRod ActionType = "FLUSH_COOLING_ROD"
	ActionOverclockRod    ActionType = "OVERCLOCK_ROD"
	ActionSwitchBranch    ActionType = "SWITCH_BRANCH"
	ActionDeliverCargo    ActionType = "DELIVER_CARGO"
)

// ReplayAction represents a single timestamped deterministic player command on the 60Hz simulation timeline.
type ReplayAction struct {
	Tick           int        `json:"tick"`
	Type           ActionType `json:"type"`
	ShieldsWatts   int        `json:"shields_watts,omitempty"`
	ArtilleryWatts int        `json:"artillery_watts,omitempty"`
	ThrustersWatts int        `json:"thrusters_watts,omitempty"`
	TargetRaiderID string     `json:"target_raider_id,omitempty"`
	BranchID       string     `json:"branch_id,omitempty"`
	CommodityID    string     `json:"commodity_id,omitempty"`
	Quantity       int        `json:"quantity,omitempty"`
}

// DeliveredCargoItem represents cargo delivered at the destination Oasis Hub upon run completion.
type DeliveredCargoItem struct {
	CommodityID string `json:"commodity_id"`
	Quantity    int    `json:"quantity"`
}

// VerifyRunRequest is the payload accepted by POST /api/v1/convoy/run/verify (and /api/v1/run/verify).
type VerifyRunRequest struct {
	RunID              string               `json:"run_id"`
	IdempotencyKey     string               `json:"idempotency_key"`
	PlayerID           string               `json:"player_id"`
	ConvoyID           string               `json:"convoy_id"`
	OriginHubID        string               `json:"origin_hub_id"`
	DestinationHubID   string               `json:"destination_hub_id"`
	Seed               int64                `json:"seed"`
	MaxReactorWatts    int                  `json:"max_reactor_watts,omitempty"`
	InitialCoolingRods int                  `json:"initial_cooling_rods,omitempty"`
	Actions            []ReplayAction       `json:"actions"`
	DeliveredCargo     []DeliveredCargoItem `json:"delivered_cargo,omitempty"`
	ClaimedScrapEarned *int64               `json:"claimed_scrap_earned,omitempty"`
	ClaimedStateHash   string               `json:"claimed_state_hash,omitempty"`

	// Cryptographic Authentication (OWASP 2025 A04: supports HMAC-SHA256 and/or AES-256-GCM AEAD)
	KeyID         string `json:"key_id,omitempty"`
	SignatureHMAC string `json:"signature_hmac,omitempty"`
	AEADNonceHex  string `json:"aead_nonce_hex,omitempty"`
	AEADCipherHex string `json:"aead_cipher_hex,omitempty"`
}

// VerifyRunResult contains the deterministic simulation outcome and cryptographic attestation.
type VerifyRunResult struct {
	Verified            bool                 `json:"verified"`
	RunID               string               `json:"run_id"`
	IdempotencyKey      string               `json:"idempotency_key"`
	PlayerID            string               `json:"player_id"`
	ConvoyID            string               `json:"convoy_id"`
	OriginHubID         string               `json:"origin_hub_id"`
	DestinationHubID    string               `json:"destination_hub_id"`
	Seed                int64                `json:"seed"`
	ActionCount         int                  `json:"action_count"`
	ScrapEarned         int64                `json:"scrap_earned"`
	CoolingRodsConsumed int                  `json:"cooling_rods_consumed"`
	DistanceTraveledM   float64              `json:"distance_traveled_m"`
	PeakReactorHeat     float64              `json:"peak_reactor_heat"`
	FinalStateHash      string               `json:"final_state_hash"`
	SignatureScheme     string               `json:"signature_scheme"`
	DeliveredCargo      []DeliveredCargoItem `json:"delivered_cargo,omitempty"`
}

// KeyRing manages cryptographic keys with rotation support (OWASP 2025 A04).
type KeyRing struct {
	activeKeyID string
	keys        map[string][]byte // 32-byte keys for HMAC-SHA256 and AES-256-GCM
}

// NewKeyRing creates a KeyRing with a 32-byte key derived from secretMaterial.
func NewKeyRing(activeKeyID string, secretMaterial string) *KeyRing {
	if activeKeyID == "" {
		activeKeyID = "chrono-key-2026-v1"
	}
	if secretMaterial == "" {
		secretMaterial = "chrono-convoy-iron-drift-sprint1-master-secret-2026"
	}
	derived := sha256.Sum256([]byte(secretMaterial))
	kr := &KeyRing{
		activeKeyID: activeKeyID,
		keys: map[string][]byte{
			activeKeyID: derived[:],
		},
	}
	return kr
}

// AddKey registers an additional rotated key in the KeyRing.
func (kr *KeyRing) AddKey(keyID string, secretMaterial string) {
	derived := sha256.Sum256([]byte(secretMaterial))
	kr.keys[keyID] = derived[:]
}

func (kr *KeyRing) resolveKey(keyID string) ([]byte, error) {
	if keyID == "" {
		keyID = kr.activeKeyID
	}
	k, ok := kr.keys[keyID]
	if !ok {
		return nil, fmt.Errorf("%w: unknown key_id %q", ErrCryptoVerificationFailed, keyID)
	}
	return k, nil
}

// CanonicalSigningPayload builds the unambiguous canonical byte slice signed by HMAC-SHA256 / authenticated by AEAD.
func CanonicalSigningPayload(req *VerifyRunRequest) ([]byte, error) {
	actionsBytes, err := json.Marshal(req.Actions)
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
	sb.Write(actionsBytes)
	return []byte(sb.String()), nil
}

// SignRequestHMAC computes the hex-encoded HMAC-SHA256 signature for a VerifyRunRequest.
func (kr *KeyRing) SignRequestHMAC(req *VerifyRunRequest) (string, error) {
	key, err := kr.resolveKey(req.KeyID)
	if err != nil {
		return "", err
	}
	payload, err := CanonicalSigningPayload(req)
	if err != nil {
		return "", err
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(payload)
	return hex.EncodeToString(mac.Sum(nil)), nil
}

// SealRequestAEAD encrypts and authenticates the canonical action log using AES-256-GCM with Associated Data (AAD).
func (kr *KeyRing) SealRequestAEAD(req *VerifyRunRequest, nonce []byte) (nonceHex string, cipherHex string, err error) {
	key, err := kr.resolveKey(req.KeyID)
	if err != nil {
		return "", "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", "", err
	}
	if len(nonce) != gcm.NonceSize() {
		nonce = make([]byte, gcm.NonceSize())
		binary.BigEndian.PutUint64(nonce[4:], uint64(req.Seed))
	}
	payload, err := CanonicalSigningPayload(req)
	if err != nil {
		return "", "", err
	}
	aad := buildAAD(req)
	sealed := gcm.Seal(nil, nonce, payload, aad)
	return hex.EncodeToString(nonce), hex.EncodeToString(sealed), nil
}

func buildAAD(req *VerifyRunRequest) []byte {
	return []byte(fmt.Sprintf("v1|%s|%s|%s|%d", req.RunID, req.PlayerID, req.ConvoyID, req.Seed))
}

// VerifyCryptographicEnvelope validates either AES-256-GCM (AEAD) or HMAC-SHA256 on the request.
func (kr *KeyRing) VerifyCryptographicEnvelope(req *VerifyRunRequest) (string, error) {
	key, err := kr.resolveKey(req.KeyID)
	if err != nil {
		return "", err
	}
	expectedPayload, err := CanonicalSigningPayload(req)
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrInvalidReplayPayload, err)
	}

	// Prefer AES-256-GCM AEAD when provided
	if req.AEADCipherHex != "" && req.AEADNonceHex != "" {
		nonce, err := hex.DecodeString(req.AEADNonceHex)
		if err != nil {
			return "", fmt.Errorf("%w: invalid aead_nonce_hex", ErrCryptoVerificationFailed)
		}
		ciphertext, err := hex.DecodeString(req.AEADCipherHex)
		if err != nil {
			return "", fmt.Errorf("%w: invalid aead_cipher_hex", ErrCryptoVerificationFailed)
		}
		block, err := aes.NewCipher(key)
		if err != nil {
			return "", ErrCryptoVerificationFailed
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil || len(nonce) != gcm.NonceSize() {
			return "", fmt.Errorf("%w: invalid GCM nonce length", ErrCryptoVerificationFailed)
		}
		plaintext, err := gcm.Open(nil, nonce, ciphertext, buildAAD(req))
		if err != nil {
			return "", fmt.Errorf("%w: AES-256-GCM tag mismatch or tampered AAD/seed", ErrCryptoVerificationFailed)
		}
		if !hmac.Equal(plaintext, expectedPayload) {
			return "", fmt.Errorf("%w: decrypted AEAD payload does not match request body", ErrCryptoVerificationFailed)
		}
		return "AES-256-GCM", nil
	}

	// Otherwise verify HMAC-SHA256
	if req.SignatureHMAC != "" {
		providedMAC, err := hex.DecodeString(strings.TrimSpace(req.SignatureHMAC))
		if err != nil {
			return "", fmt.Errorf("%w: invalid hex in signature_hmac", ErrCryptoVerificationFailed)
		}
		mac := hmac.New(sha256.New, key)
		mac.Write(expectedPayload)
		expectedMAC := mac.Sum(nil)
		if !hmac.Equal(providedMAC, expectedMAC) {
			return "", fmt.Errorf("%w: HMAC-SHA256 signature mismatch", ErrCryptoVerificationFailed)
		}
		return "HMAC-SHA256", nil
	}

	return "", fmt.Errorf("%w: missing signature_hmac or aead_cipher_hex", ErrCryptoVerificationFailed)
}

// splitMix64 provides fast, platform-independent deterministic PRNG seeded by the run seed.
type splitMix64 struct {
	state uint64
}

func newSplitMix64(seed int64) *splitMix64 {
	return &splitMix64{state: uint64(seed) ^ 0x9E3779B97F4A7C15}
}

func (rng *splitMix64) nextUint64() uint64 {
	rng.state += 0x9E3779B97F4A7C15
	z := rng.state
	z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
	z = (z ^ (z >> 27)) * 0x94D049BB133111EB
	return z ^ (z >> 31)
}

func (rng *splitMix64) nextIntRange(minVal, maxVal int64) int64 {
	if maxVal <= minVal {
		return minVal
	}
	span := uint64(maxVal - minVal + 1)
	return minVal + int64(rng.nextUint64()%span)
}

// Verifier performs ABAC ownership validation, cryptographic verification, and deterministic run simulation.
type Verifier struct {
	keyRing *KeyRing
}

func NewVerifier(keyRing *KeyRing) *Verifier {
	if keyRing == nil {
		keyRing = NewKeyRing("", "")
	}
	return &Verifier{keyRing: keyRing}
}

func (v *Verifier) KeyRing() *KeyRing {
	return v.keyRing
}

// VerifyAndReplay validates ABAC ownership, cryptographic authenticity, and deterministic physics/economy invariants.
func (v *Verifier) VerifyAndReplay(authenticatedPlayerID string, req *VerifyRunRequest) (*VerifyRunResult, error) {
	if req == nil {
		return nil, ErrInvalidReplayPayload
	}
	// 1. Input validation
	if strings.TrimSpace(req.RunID) == "" || strings.TrimSpace(req.PlayerID) == "" || strings.TrimSpace(req.ConvoyID) == "" {
		return nil, fmt.Errorf("%w: run_id, player_id, and convoy_id are required", ErrInvalidReplayPayload)
	}
	if req.Seed <= 0 {
		return nil, fmt.Errorf("%w: seed must be a positive integer", ErrInvalidReplayPayload)
	}
	if len(req.Actions) == 0 {
		return nil, fmt.Errorf("%w: action log cannot be empty", ErrInvalidReplayPayload)
	}
	if req.IdempotencyKey == "" {
		req.IdempotencyKey = "run_verify_" + req.RunID
	}

	// 2. OWASP 2025 A01: Attribute-Based Access Control (ABAC) / Anti-IDOR enforcement
	if authenticatedPlayerID == "" || authenticatedPlayerID != req.PlayerID {
		return nil, fmt.Errorf("%w: authenticated player %q cannot submit run for player %q",
			ErrForbiddenIDOR, authenticatedPlayerID, req.PlayerID)
	}

	// 3. OWASP 2025 A04: Cryptographic verification (AES-256-GCM AEAD or HMAC-SHA256)
	scheme, err := v.keyRing.VerifyCryptographicEnvelope(req)
	if err != nil {
		return nil, err
	}

	// 4. Deterministic Seed + Action-Log Simulation
	rng := newSplitMix64(req.Seed)
	baseMaxWatts := req.MaxReactorWatts
	if baseMaxWatts <= 0 {
		baseMaxWatts = DefaultMaxReactorWatts
	}
	coolingRodsRemaining := req.InitialCoolingRods
	if coolingRodsRemaining <= 0 {
		coolingRodsRemaining = 10
	}

	shieldsW := 30
	artilleryW := 35
	thrustersW := 35
	overclockUntilTick := -1
	reactorHeat := 20.0
	peakHeat := reactorHeat
	distanceM := 0.0
	scrapEarned := int64(0)
	rodsConsumed := 0
	branchSalvageMult := 1.0
	prevTick := 0
	var delivered []DeliveredCargoItem

	stateHasher := sha256.New()
	_, _ = fmt.Fprintf(stateHasher, "seed:%d|convoy:%s|", req.Seed, req.ConvoyID)

	for idx, act := range req.Actions {
		if act.Tick < prevTick || act.Tick > MaxAllowedTicks {
			return nil, fmt.Errorf("%w: action[%d] tick=%d (prev=%d)", ErrNonMonotonicTick, idx, act.Tick, prevTick)
		}
		deltaTicks := act.Tick - prevTick
		dtSec := float64(deltaTicks) / 60.0
		prevTick = act.Tick

		// Advance continuous spline kinematics and thermal model over dtSec
		if dtSec > 0 {
			speedMult := 0.70 + 0.0125*float64(thrustersW)
			cruiseSpeedMps := 28.0 * speedMult
			distanceM += cruiseSpeedMps * dtSec

			totalW := shieldsW + artilleryW + thrustersW
			if totalW > baseMaxWatts {
				reactorHeat += float64(totalW-baseMaxWatts) * 0.18 * dtSec
			} else if totalW > 90 {
				reactorHeat += float64(totalW-90) * 0.08 * dtSec
			} else {
				reactorHeat = math.Max(15.0, reactorHeat-3.2*dtSec)
			}
			if reactorHeat > peakHeat {
				peakHeat = reactorHeat
			}
			if reactorHeat >= 100.0 {
				return nil, fmt.Errorf("%w: reactor heat reached %.2f at tick %d", ErrThermalMeltdownOrRodShortage, reactorHeat, act.Tick)
			}
		}

		effectiveMaxWatts := baseMaxWatts
		if act.Tick <= overclockUntilTick {
			effectiveMaxWatts += OverclockBonusWatts
		}

		switch act.Type {
		case ActionAllocateWatts:
			if act.ShieldsWatts < 0 || act.ArtilleryWatts < 0 || act.ThrustersWatts < 0 {
				return nil, fmt.Errorf("%w: negative subsystem watt allocation at tick %d", ErrWattBudgetExceeded, act.Tick)
			}
			if act.ShieldsWatts > SubsystemMaxWatts || act.ArtilleryWatts > SubsystemMaxWatts || act.ThrustersWatts > SubsystemMaxWatts {
				return nil, fmt.Errorf("%w: subsystem exceeds %dW cap at tick %d", ErrWattBudgetExceeded, SubsystemMaxWatts, act.Tick)
			}
			sumW := act.ShieldsWatts + act.ArtilleryWatts + act.ThrustersWatts
			if sumW > effectiveMaxWatts {
				return nil, fmt.Errorf("%w: allocated %dW > max %dW at tick %d", ErrWattBudgetExceeded, sumW, effectiveMaxWatts, act.Tick)
			}
			shieldsW = act.ShieldsWatts
			artilleryW = act.ArtilleryWatts
			thrustersW = act.ThrustersWatts

		case ActionOverclockRod:
			if coolingRodsRemaining <= 0 {
				return nil, fmt.Errorf("%w: no cooling rods left for overclock at tick %d", ErrThermalMeltdownOrRodShortage, act.Tick)
			}
			coolingRodsRemaining--
			rodsConsumed++
			overclockUntilTick = act.Tick + (20 * 60) // 20 seconds at 60Hz

		case ActionFlushCoolingRod:
			if coolingRodsRemaining <= 0 {
				return nil, fmt.Errorf("%w: no cooling rods left for thermal flush at tick %d", ErrThermalMeltdownOrRodShortage, act.Tick)
			}
			coolingRodsRemaining--
			rodsConsumed++
			reactorHeat = math.Max(15.0, reactorHeat-45.0)

		case ActionSwitchBranch:
			if act.BranchID == "high_threat_scrap_canyon" {
				branchSalvageMult = 1.85
			} else {
				branchSalvageMult = 1.0
			}

		case ActionFireTurret:
			reactorHeat += 1.15
			if reactorHeat > peakHeat {
				peakHeat = reactorHeat
			}
			if reactorHeat >= 100.0 {
				return nil, fmt.Errorf("%w: artillery fire overheated reactor at tick %d", ErrThermalMeltdownOrRodShortage, act.Tick)
			}
			baseBounty := rng.nextIntRange(25, 65)
			artilleryMult := 0.75 + 0.010*float64(artilleryW)
			reward := int64(math.Round(float64(baseBounty) * artilleryMult * branchSalvageMult))
			scrapEarned += reward

		case ActionRamRaider:
			baseBounty := rng.nextIntRange(40, 90)
			thrustMult := 0.60 + 0.0167*float64(thrustersW)
			reward := int64(math.Round(float64(baseBounty) * thrustMult * branchSalvageMult))
			scrapEarned += reward

		case ActionDeliverCargo:
			if act.Quantity <= 0 || act.Quantity > 10000 {
				return nil, fmt.Errorf("%w: invalid DELIVER_CARGO quantity %d at tick %d", ErrInvalidReplayPayload, act.Quantity, act.Tick)
			}
			delivered = append(delivered, DeliveredCargoItem{
				CommodityID: act.CommodityID,
				Quantity:    act.Quantity,
			})

		default:
			return nil, fmt.Errorf("%w: unsupported action type %q at tick %d", ErrInvalidReplayPayload, act.Type, act.Tick)
		}

		_, _ = fmt.Fprintf(stateHasher, "%d:%s:%d:%d:%d:%d:%.2f|",
			act.Tick, act.Type, shieldsW, artilleryW, thrustersW, scrapEarned, reactorHeat)
	}

	// Merge any top-level DeliveredCargo items if not already specified in actions
	if len(delivered) == 0 && len(req.DeliveredCargo) > 0 {
		delivered = append(delivered, req.DeliveredCargo...)
	}

	// Baseline transit completion bonus
	scrapEarned += 50
	finalHash := hex.EncodeToString(stateHasher.Sum(nil))

	// 5. Verify client-claimed values against deterministic simulation output
	if req.ClaimedScrapEarned != nil && *req.ClaimedScrapEarned != scrapEarned {
		return nil, fmt.Errorf("%w: claimed scrap %d != deterministic replay scrap %d",
			ErrTamperedReplayState, *req.ClaimedScrapEarned, scrapEarned)
	}
	if req.ClaimedStateHash != "" && !strings.EqualFold(req.ClaimedStateHash, finalHash) {
		return nil, fmt.Errorf("%w: claimed state hash %s != deterministic hash %s",
			ErrTamperedReplayState, req.ClaimedStateHash, finalHash)
	}

	return &VerifyRunResult{
		Verified:            true,
		RunID:               req.RunID,
		IdempotencyKey:      req.IdempotencyKey,
		PlayerID:            req.PlayerID,
		ConvoyID:            req.ConvoyID,
		OriginHubID:         req.OriginHubID,
		DestinationHubID:    req.DestinationHubID,
		Seed:                req.Seed,
		ActionCount:         len(req.Actions),
		ScrapEarned:         scrapEarned,
		CoolingRodsConsumed: rodsConsumed,
		DistanceTraveledM:   math.Round(distanceM*100.0) / 100.0,
		PeakReactorHeat:     math.Round(peakHeat*100.0) / 100.0,
		FinalStateHash:      finalHash,
		SignatureScheme:     scheme,
		DeliveredCargo:      delivered,
	}, nil
}
