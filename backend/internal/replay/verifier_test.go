package replay

import (
	"errors"
	"testing"
)

func sampleValidRequest() *VerifyRunRequest {
	return &VerifyRunRequest{
		RunID:              "run_sprint1_001",
		IdempotencyKey:     "idem_run_sprint1_001",
		PlayerID:           "player_cmdr_riki",
		ConvoyID:           "convoy_iron_drift_01",
		OriginHubID:        "hydro_haven",
		DestinationHubID:   "iron_refinery",
		Seed:               987654321,
		MaxReactorWatts:    100,
		InitialCoolingRods: 6,
		Actions: []ReplayAction{
			{Tick: 0, Type: ActionAllocateWatts, ShieldsWatts: 30, ArtilleryWatts: 40, ThrustersWatts: 30},
			{Tick: 120, Type: ActionSwitchBranch, BranchID: "high_threat_scrap_canyon"},
			{Tick: 240, Type: ActionFireTurret, TargetRaiderID: "raider_skiff_01"},
			{Tick: 360, Type: ActionRamRaider, TargetRaiderID: "raider_skiff_02"},
			{Tick: 480, Type: ActionOverclockRod},
			{Tick: 485, Type: ActionAllocateWatts, ShieldsWatts: 40, ArtilleryWatts: 50, ThrustersWatts: 40}, // 130W valid during overclock
			{Tick: 900, Type: ActionFlushCoolingRod},
			{Tick: 1200, Type: ActionDeliverCargo, CommodityID: "purified_water", Quantity: 40},
		},
	}
}

func TestVerifier_ValidHMACAndDeterministicReplay(t *testing.T) {
	kr := NewKeyRing("chrono-key-2026-v1", "test-secret-key-material-2026")
	v := NewVerifier(kr)

	req1 := sampleValidRequest()
	sig, err := kr.SignRequestHMAC(req1)
	if err != nil {
		t.Fatalf("SignRequestHMAC failed: %v", err)
	}
	req1.SignatureHMAC = sig

	res1, err := v.VerifyAndReplay("player_cmdr_riki", req1)
	if err != nil {
		t.Fatalf("VerifyAndReplay failed: %v", err)
	}
	if !res1.Verified || res1.SignatureScheme != "HMAC-SHA256" {
		t.Fatalf("expected verified HMAC-SHA256 result, got %+v", res1)
	}
	if res1.CoolingRodsConsumed != 2 {
		t.Fatalf("expected 2 cooling rods consumed, got %d", res1.CoolingRodsConsumed)
	}

	// Second replay with identical seed & actions must produce identical FinalStateHash and ScrapEarned
	req2 := sampleValidRequest()
	req2.SignatureHMAC = sig
	res2, err := v.VerifyAndReplay("player_cmdr_riki", req2)
	if err != nil {
		t.Fatalf("second VerifyAndReplay failed: %v", err)
	}
	if res1.FinalStateHash != res2.FinalStateHash || res1.ScrapEarned != res2.ScrapEarned {
		t.Fatalf("non-deterministic replay output: res1=%+v vs res2=%+v", res1, res2)
	}
}

func TestVerifier_ValidAEADAndTamperRejection(t *testing.T) {
	kr := NewKeyRing("chrono-key-2026-v1", "test-secret-key-material-2026")
	v := NewVerifier(kr)

	req := sampleValidRequest()
	nonceHex, cipherHex, err := kr.SealRequestAEAD(req, nil)
	if err != nil {
		t.Fatalf("SealRequestAEAD failed: %v", err)
	}
	req.AEADNonceHex = nonceHex
	req.AEADCipherHex = cipherHex

	res, err := v.VerifyAndReplay("player_cmdr_riki", req)
	if err != nil {
		t.Fatalf("VerifyAndReplay with AEAD failed: %v", err)
	}
	if res.SignatureScheme != "AES-256-GCM" {
		t.Fatalf("expected AES-256-GCM scheme, got %s", res.SignatureScheme)
	}

	// Tamper with seed after sealing AEAD (AAD mismatch)
	tamperedSeedReq := sampleValidRequest()
	tamperedSeedReq.Seed = 111111111
	tamperedSeedReq.AEADNonceHex = nonceHex
	tamperedSeedReq.AEADCipherHex = cipherHex
	if _, err := v.VerifyAndReplay("player_cmdr_riki", tamperedSeedReq); !errors.Is(err, ErrCryptoVerificationFailed) {
		t.Fatalf("expected ErrCryptoVerificationFailed on tampered seed, got %v", err)
	}
}

func TestVerifier_RejectsIDORAndWattViolationAndClaimedRewardTamper(t *testing.T) {
	kr := NewKeyRing("chrono-key-2026-v1", "test-secret-key-material-2026")
	v := NewVerifier(kr)

	// 1. ABAC / IDOR check: another player attempts to verify a run belonging to player_cmdr_riki
	reqIDOR := sampleValidRequest()
	sig, _ := kr.SignRequestHMAC(reqIDOR)
	reqIDOR.SignatureHMAC = sig
	if _, err := v.VerifyAndReplay("player_attacker_99", reqIDOR); !errors.Is(err, ErrForbiddenIDOR) {
		t.Fatalf("expected ErrForbiddenIDOR, got %v", err)
	}

	// 2. Reactor Watt conservation violation: allocating 120W without overclock
	reqWatt := sampleValidRequest()
	reqWatt.Actions = []ReplayAction{
		{Tick: 10, Type: ActionAllocateWatts, ShieldsWatts: 50, ArtilleryWatts: 40, ThrustersWatts: 30}, // 120W > 100W
	}
	reqWatt.SignatureHMAC, _ = kr.SignRequestHMAC(reqWatt)
	if _, err := v.VerifyAndReplay("player_cmdr_riki", reqWatt); !errors.Is(err, ErrWattBudgetExceeded) {
		t.Fatalf("expected ErrWattBudgetExceeded, got %v", err)
	}

	// 3. Client claims inflated scrap reward
	reqInflated := sampleValidRequest()
	inflated := int64(999999)
	reqInflated.ClaimedScrapEarned = &inflated
	reqInflated.SignatureHMAC, _ = kr.SignRequestHMAC(reqInflated)
	if _, err := v.VerifyAndReplay("player_cmdr_riki", reqInflated); !errors.Is(err, ErrTamperedReplayState) {
		t.Fatalf("expected ErrTamperedReplayState on inflated scrap claim, got %v", err)
	}
}
