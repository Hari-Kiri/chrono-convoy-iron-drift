package qa

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// resolveTestTarget spins up the deterministic verification server or targets CONVOY_BACKEND_URL if set.
func resolveTestTarget(t *testing.T) (string, *ReplayVerifierEngine, func()) {
	t.Helper()
	engine := NewReplayVerifierEngine(DefaultHMACSecret)
	if envURL := strings.TrimSpace(os.Getenv("CONVOY_BACKEND_URL")); envURL != "" {
		return strings.TrimRight(envURL, "/") + "/api/v1/convoy/run/verify", engine, func() {}
	}
	srv := httptest.NewServer(NewReplayVerifyMux(engine))
	return srv.URL + "/api/v1/convoy/run/verify", engine, srv.Close
}

func buildValidSignedReplay(runID, idemKey, playerID string, seed int64, scrapEarned int64) ReplayVerifyRequest {
	req := ReplayVerifyRequest{
		RunID:               runID,
		IdempotencyKey:      idemKey,
		PlayerID:            playerID,
		ConvoyID:            "convoy_qa_01",
		OriginHubID:         "hydro_haven",
		DestinationHubID:    "iron_refinery",
		Seed:                seed,
		TotalTicks:          600,
		ScrapEarned:         scrapEarned,
		CoolingRodsConsumed: 3,
		DistanceTraveledM:   2880.0,
		Actions: []ReplayAction{
			{
				Tick:            0,
				ActionType:      "REACTOR_ALLOC",
				ShieldsWatts:    30,
				ArtilleryWatts:  35,
				ThrustersWatts:  35,
				OverclockActive: false,
				ScrapDelta:      0,
			},
			{
				Tick:            180,
				ActionType:      "RAIDER_KILL",
				ShieldsWatts:    40,
				ArtilleryWatts:  45,
				ThrustersWatts:  45,
				OverclockActive: true,
				ScrapDelta:      scrapEarned / 2,
			},
			{
				Tick:            420,
				ActionType:      "RAIDER_KILL",
				ShieldsWatts:    30,
				ArtilleryWatts:  35,
				ThrustersWatts:  35,
				OverclockActive: false,
				ScrapDelta:      scrapEarned - (scrapEarned / 2),
			},
		},
		SignatureScheme: "HMAC-SHA256",
	}
	req.FinalStateHash = ComputeDeterministicStateHash(&req)
	req.Signature = SignReplayPayload(&req, DefaultHMACSecret)
	return req
}

func postReplayJSON(client *http.Client, endpoint string, body []byte) (int, ReplayVerifyResponse, time.Duration, error) {
	start := time.Now()
	resp, err := client.Post(endpoint, "application/json", bytes.NewReader(body))
	elapsed := time.Since(start)
	if err != nil {
		return 0, ReplayVerifyResponse{}, elapsed, err
	}
	defer resp.Body.Close()
	var parsed ReplayVerifyResponse
	_ = json.NewDecoder(resp.Body).Decode(&parsed)
	return resp.StatusCode, parsed, elapsed, nil
}

func TestValidDeterministicReplayVerification(t *testing.T) {
	endpoint, engine, cleanup := resolveTestTarget(t)
	defer cleanup()

	client := &http.Client{Timeout: 5 * time.Second}
	initialBalance := engine.GetWalletBalance("player_aegis_qa")

	req := buildValidSignedReplay("run_valid_001", "idem_valid_001", "player_aegis_qa", 42069, 180)
	raw, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("failed to marshal valid request: %v", err)
	}

	status, parsed, elapsed, err := postReplayJSON(client, endpoint, raw)
	if err != nil {
		t.Fatalf("HTTP POST failed: %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d (%s: %s)", status, parsed.ErrorCode, parsed.ErrorMessage)
	}
	if !parsed.Verified || !parsed.LedgerCredited || parsed.Duplicate {
		t.Fatalf("unexpected verify response flags: %+v", parsed)
	}
	expectedBalance := initialBalance + 180
	if parsed.NewWalletBalance != expectedBalance {
		t.Fatalf("expected new wallet balance %d, got %d", expectedBalance, parsed.NewWalletBalance)
	}
	t.Logf("[PASS] Valid deterministic replay verified in %v (new_wallet_balance=%d, state_hash=%s)",
		elapsed, parsed.NewWalletBalance, parsed.ServerStateHash)
}

func TestConcurrentDuplicateReplayAttackIdempotency(t *testing.T) {
	endpoint, engine, cleanup := resolveTestTarget(t)
	defer cleanup()

	client := &http.Client{Timeout: 5 * time.Second}
	initialBalance := engine.GetWalletBalance("player_cmdr_riki")
	const scrapReward = int64(250)
	req := buildValidSignedReplay("run_race_dup_001", "idem_race_dup_001", "player_cmdr_riki", 998877, scrapReward)
	raw, _ := json.Marshal(req)

	const goroutines = 100
	var startBarrier sync.WaitGroup
	startBarrier.Add(1)
	var doneWg sync.WaitGroup
	doneWg.Add(goroutines)

	var okCount atomic.Int32
	var conflictCount atomic.Int32
	var otherStatusCount atomic.Int32

	for g := 0; g < goroutines; g++ {
		go func() {
			defer doneWg.Done()
			startBarrier.Wait()
			status, parsed, _, err := postReplayJSON(client, endpoint, raw)
			if err != nil {
				otherStatusCount.Add(1)
				return
			}
			if status == http.StatusOK && parsed.Verified && parsed.LedgerCredited && !parsed.Duplicate {
				okCount.Add(1)
			} else if status == http.StatusConflict && parsed.Duplicate && !parsed.LedgerCredited {
				conflictCount.Add(1)
			} else {
				otherStatusCount.Add(1)
			}
		}()
	}

	startBarrier.Done()
	doneWg.Wait()

	if okCount.Load() != 1 {
		t.Fatalf("Double-spend or idempotency failure: expected exactly 1 HTTP 200 OK credit, got %d", okCount.Load())
	}
	if conflictCount.Load() != goroutines-1 {
		t.Fatalf("Expected %d HTTP 409 Conflict rejections, got %d (other=%d)",
			goroutines-1, conflictCount.Load(), otherStatusCount.Load())
	}
	finalBalance := engine.GetWalletBalance("player_cmdr_riki")
	if finalBalance != initialBalance+scrapReward {
		t.Fatalf("Wallet balance corrupted under concurrent duplicate replay: initial=%d, final=%d, expected=%d",
			initialBalance, finalBalance, initialBalance+scrapReward)
	}
	t.Logf("[PASS] 100-Goroutine Duplicate Replay Race: 1 Credited (200 OK), 99 Rejected (409 Conflict), Wallet %d -> %d",
		initialBalance, finalBalance)
}

func TestTamperedStateHashAndSignatureRejection(t *testing.T) {
	endpoint, engine, cleanup := resolveTestTarget(t)
	defer cleanup()

	client := &http.Client{Timeout: 5 * time.Second}
	initialBalance := engine.GetWalletBalance("player_aegis_qa")

	// Case A: Client inflates ScrapEarned after computing state hash & signature
	inflated := buildValidSignedReplay("run_tamper_01", "idem_tamper_01", "player_aegis_qa", 12345, 150)
	inflated.ScrapEarned = 9999
	rawA, _ := json.Marshal(inflated)
	statusA, respA, _, _ := postReplayJSON(client, endpoint, rawA)
	if statusA != http.StatusUnprocessableEntity {
		t.Fatalf("expected 422 for inflated scrap_earned, got %d", statusA)
	}

	// Case B: Client tampers with FinalStateHash but re-signs with wrong secret
	badHash := buildValidSignedReplay("run_tamper_02", "idem_tamper_02", "player_aegis_qa", 12345, 150)
	badHash.FinalStateHash = strings.Repeat("deadbeef", 8)
	rawB, _ := json.Marshal(badHash)
	statusB, respB, _, _ := postReplayJSON(client, endpoint, rawB)
	if statusB != http.StatusUnprocessableEntity || respB.ErrorCode != "STATE_HASH_MISMATCH" {
		t.Fatalf("expected 422 STATE_HASH_MISMATCH, got %d (%s)", statusB, respB.ErrorCode)
	}

	// Case C: Valid state hash, forged HMAC signature
	badSig := buildValidSignedReplay("run_tamper_03", "idem_tamper_03", "player_aegis_qa", 12345, 150)
	badSig.Signature = SignReplayPayload(&badSig, "attacker-forged-secret-key")
	rawC, _ := json.Marshal(badSig)
	statusC, respC, _, _ := postReplayJSON(client, endpoint, rawC)
	if statusC != http.StatusUnprocessableEntity || respC.ErrorCode != "INVALID_SIGNATURE" {
		t.Fatalf("expected 422 INVALID_SIGNATURE, got %d (%s)", statusC, respC.ErrorCode)
	}

	if engine.GetWalletBalance("player_aegis_qa") != initialBalance {
		t.Fatalf("Tampered requests must never mutate player wallet balance")
	}
	t.Logf("[PASS] Tampered payloads rejected cleanly: codes=[%s, %s, %s]",
		respA.ErrorCode, respB.ErrorCode, respC.ErrorCode)
}

func TestTickManipulationAndOutOfOrderActionsRejection(t *testing.T) {
	endpoint, _, cleanup := resolveTestTarget(t)
	defer cleanup()
	client := &http.Client{Timeout: 5 * time.Second}

	// Case A: Non-monotonic retrograde tick (tick 300 followed by tick 120)
	retro := buildValidSignedReplay("run_tick_01", "idem_tick_01", "player_aegis_qa", 777, 100)
	retro.Actions[1].Tick = 300
	retro.Actions[2].Tick = 120
	retro.FinalStateHash = ComputeDeterministicStateHash(&retro)
	retro.Signature = SignReplayPayload(&retro, DefaultHMACSecret)
	rawA, _ := json.Marshal(retro)
	statusA, respA, _, _ := postReplayJSON(client, endpoint, rawA)
	if statusA != http.StatusUnprocessableEntity || respA.ErrorCode != "NON_MONOTONIC_TICK" {
		t.Fatalf("expected 422 NON_MONOTONIC_TICK, got %d (%s)", statusA, respA.ErrorCode)
	}

	// Case B: Negative action tick
	negTick := buildValidSignedReplay("run_tick_02", "idem_tick_02", "player_aegis_qa", 777, 100)
	negTick.Actions[0].Tick = -5
	negTick.FinalStateHash = ComputeDeterministicStateHash(&negTick)
	negTick.Signature = SignReplayPayload(&negTick, DefaultHMACSecret)
	rawB, _ := json.Marshal(negTick)
	statusB, respB, _, _ := postReplayJSON(client, endpoint, rawB)
	if statusB != http.StatusUnprocessableEntity || respB.ErrorCode != "TICK_OUT_OF_RANGE" {
		t.Fatalf("expected 422 TICK_OUT_OF_RANGE, got %d (%s)", statusB, respB.ErrorCode)
	}

	// Case C: Action tick exceeding declared total_ticks
	overTick := buildValidSignedReplay("run_tick_03", "idem_tick_03", "player_aegis_qa", 777, 100)
	overTick.Actions[2].Tick = 9999
	overTick.FinalStateHash = ComputeDeterministicStateHash(&overTick)
	overTick.Signature = SignReplayPayload(&overTick, DefaultHMACSecret)
	rawC, _ := json.Marshal(overTick)
	statusC, respC, _, _ := postReplayJSON(client, endpoint, rawC)
	if statusC != http.StatusUnprocessableEntity || respC.ErrorCode != "TICK_OUT_OF_RANGE" {
		t.Fatalf("expected 422 TICK_OUT_OF_RANGE, got %d (%s)", statusC, respC.ErrorCode)
	}

	t.Logf("[PASS] Tick manipulation attacks rejected: codes=[%s, %s, %s]",
		respA.ErrorCode, respB.ErrorCode, respC.ErrorCode)
}

func TestNegativeAndOverflowResourceDeltaRejection(t *testing.T) {
	endpoint, _, cleanup := resolveTestTarget(t)
	defer cleanup()
	client := &http.Client{Timeout: 5 * time.Second}

	cases := []struct {
		name         string
		mutate       func(r *ReplayVerifyRequest)
		expectedCode string
	}{
		{
			name: "negative_scrap_earned",
			mutate: func(r *ReplayVerifyRequest) {
				r.ScrapEarned = -500
			},
			expectedCode: "INVALID_SCRAP_DELTA",
		},
		{
			name: "int64_max_scrap_overflow",
			mutate: func(r *ReplayVerifyRequest) {
				r.ScrapEarned = math.MaxInt64
			},
			expectedCode: "INVALID_SCRAP_DELTA",
		},
		{
			name: "negative_cooling_rods",
			mutate: func(r *ReplayVerifyRequest) {
				r.CoolingRodsConsumed = -2
			},
			expectedCode: "INVALID_COOLING_RODS",
		},
		{
			name: "reactor_budget_exceeded_without_overclock",
			mutate: func(r *ReplayVerifyRequest) {
				r.Actions[0].OverclockActive = false
				r.Actions[0].ShieldsWatts = 45
				r.Actions[0].ArtilleryWatts = 45
				r.Actions[0].ThrustersWatts = 45 // 135W > 100W base pool
			},
			expectedCode: "REACTOR_BUDGET_EXCEEDED",
		},
		{
			name: "negative_subsystem_watts",
			mutate: func(r *ReplayVerifyRequest) {
				r.Actions[0].ShieldsWatts = -20
				r.Actions[0].ArtilleryWatts = 60
				r.Actions[0].ThrustersWatts = 60
			},
			expectedCode: "NEGATIVE_REACTOR_WATTS",
		},
		{
			name: "unpaid_overclock_cooling_rod_sink",
			mutate: func(r *ReplayVerifyRequest) {
				r.CoolingRodsConsumed = 0 // Overclock active on action[1] but 0 rods consumed!
			},
			expectedCode: "UNPAID_OVERCLOCK_COOLING_ROD_SINK",
		},
	}

	for i, tc := range cases {
		req := buildValidSignedReplay(
			fmt.Sprintf("run_bound_%d", i),
			fmt.Sprintf("idem_bound_%d", i),
			"player_aegis_qa",
			555,
			120,
		)
		tc.mutate(&req)
		req.FinalStateHash = ComputeDeterministicStateHash(&req)
		req.Signature = SignReplayPayload(&req, DefaultHMACSecret)

		raw, _ := json.Marshal(req)
		status, resp, _, err := postReplayJSON(client, endpoint, raw)
		if err != nil {
			t.Fatalf("case %s HTTP error: %v", tc.name, err)
		}
		if status != http.StatusUnprocessableEntity || resp.ErrorCode != tc.expectedCode {
			t.Fatalf("case %s expected 422 %s, got %d (%s: %s)",
				tc.name, tc.expectedCode, status, resp.ErrorCode, resp.ErrorMessage)
		}
	}
	t.Logf("[PASS] All %d negative/overflow/reactor-budget boundary exploits rejected with 422", len(cases))
}

func TestMalformedJSONAndOversizedPayloadRejection(t *testing.T) {
	endpoint, _, cleanup := resolveTestTarget(t)
	defer cleanup()
	client := &http.Client{Timeout: 5 * time.Second}

	// 1. Empty body -> 400 Bad Request
	statusEmpty, respEmpty, _, _ := postReplayJSON(client, endpoint, []byte{})
	if statusEmpty != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty payload, got %d", statusEmpty)
	}

	// 2. Truncated / malformed JSON -> 400 Bad Request
	statusBadJSON, respBadJSON, _, _ := postReplayJSON(client, endpoint, []byte(`{"run_id": "broken", "seed": `))
	if statusBadJSON != http.StatusBadRequest {
		t.Fatalf("expected 400 for malformed JSON, got %d", statusBadJSON)
	}

	// 3. Unknown injected JSON field -> 400 Bad Request (strict schema enforcement)
	statusUnknownField, _, _, _ := postReplayJSON(client, endpoint, []byte(`{"run_id":"r1","admin_override":true}`))
	if statusUnknownField != http.StatusBadRequest {
		t.Fatalf("expected 400 for unknown field injection, got %d", statusUnknownField)
	}

	// 4. Oversized 1.5 MiB payload -> 413 Request Entity Too Large
	oversized := bytes.Repeat([]byte("A"), 1536*1024)
	statusLarge, respLarge, _, _ := postReplayJSON(client, endpoint, oversized)
	if statusLarge != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413 for 1.5 MiB payload, got %d", statusLarge)
	}

	t.Logf("[PASS] Malformed & oversized payloads rejected: empty=%s, badJSON=%s, oversized=%s (%d)",
		respEmpty.ErrorCode, respBadJSON.ErrorCode, respLarge.ErrorCode, statusLarge)
}

func TestHighConcurrencyLoadAndLatencyPercentiles(t *testing.T) {
	endpoint, engine, cleanup := resolveTestTarget(t)
	defer cleanup()

	const totalRequests = 2000
	const workerCount = 64

	transport := &http.Transport{
		MaxIdleConns:        256,
		MaxIdleConnsPerHost: 256,
		IdleConnTimeout:     30 * time.Second,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   10 * time.Second,
	}

	initialBalance := engine.GetWalletBalance("player_aegis_qa")

	type job struct {
		id      int
		payload []byte
		kind    string // "valid", "duplicate", "tampered"
		scrap   int64
	}

	jobs := make(chan job, totalRequests)
	var expectedUniqueScrapTotal int64

	// Pre-build a shared duplicate payload that multiple workers will hammer concurrently
	sharedDupReq := buildValidSignedReplay("run_load_shared_dup", "idem_load_shared_dup", "player_aegis_qa", 20261026, 100)
	sharedDupRaw, _ := json.Marshal(sharedDupReq)
	expectedUniqueScrapTotal += 100

	for i := 0; i < totalRequests; i++ {
		switch {
		case i%10 == 0:
			// 10% concurrent duplicate attacks on the shared run
			jobs <- job{id: i, payload: sharedDupRaw, kind: "duplicate", scrap: 100}
		case i%10 == 1:
			// 10% tampered signature/state hash attacks
			bad := buildValidSignedReplay(
				fmt.Sprintf("run_load_bad_%d", i),
				fmt.Sprintf("idem_load_bad_%d", i),
				"player_aegis_qa",
				int64(10000+i),
				140,
			)
			bad.ScrapEarned = 9999 // Tamper after signing
			raw, _ := json.Marshal(bad)
			jobs <- job{id: i, payload: raw, kind: "tampered", scrap: 0}
		default:
			// 80% unique valid deterministic runs
			scrap := int64(40 + (i % 120))
			valid := buildValidSignedReplay(
				fmt.Sprintf("run_load_ok_%d", i),
				fmt.Sprintf("idem_load_ok_%d", i),
				"player_aegis_qa",
				int64(50000+i),
				scrap,
			)
			raw, _ := json.Marshal(valid)
			expectedUniqueScrapTotal += scrap
			jobs <- job{id: i, payload: raw, kind: "valid", scrap: scrap}
		}
	}
	close(jobs)

	latencies := make([]time.Duration, totalRequests)
	var status200 atomic.Int32
	var status409 atomic.Int32
	var status422 atomic.Int32
	var unexpectedErrors atomic.Int32

	suiteStart := time.Now()
	var wg sync.WaitGroup
	wg.Add(workerCount)
	for w := 0; w < workerCount; w++ {
		go func() {
			defer wg.Done()
			for j := range jobs {
				status, _, elapsed, err := postReplayJSON(client, endpoint, j.payload)
				latencies[j.id] = elapsed
				if err != nil {
					unexpectedErrors.Add(1)
					continue
				}
				switch status {
				case http.StatusOK:
					status200.Add(1)
				case http.StatusConflict:
					status409.Add(1)
				case http.StatusUnprocessableEntity:
					status422.Add(1)
				default:
					unexpectedErrors.Add(1)
				}
			}
		}()
	}
	wg.Wait()
	totalElapsed := time.Since(suiteStart)

	if unexpectedErrors.Load() != 0 {
		t.Fatalf("Expected 0 unexpected HTTP/transport errors during load test, got %d", unexpectedErrors.Load())
	}

	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	p50 := latencies[int(float64(totalRequests)*0.50)]
	p95 := latencies[int(float64(totalRequests)*0.95)]
	p99 := latencies[int(float64(totalRequests)*0.99)]
	maxLat := latencies[totalRequests-1]
	rps := float64(totalRequests) / totalElapsed.Seconds()

	finalBalance := engine.GetWalletBalance("player_aegis_qa")
	expectedFinalBalance := initialBalance + expectedUniqueScrapTotal
	if finalBalance != expectedFinalBalance {
		t.Fatalf("ACID Wallet Ledger mismatch after 2,000 concurrent requests: got %d, expected %d",
			finalBalance, expectedFinalBalance)
	}

	if p99 > 50*time.Millisecond {
		t.Fatalf("p99 latency %v exceeded 50ms SLA budget", p99)
	}

	t.Logf("[LOAD METRICS] Requests=%d | Workers=%d | WallTime=%v | Throughput=%.1f req/s | p50=%v | p95=%v | p99=%v | max=%v | 200_OK=%d | 409_Conflict=%d | 422_Rejected=%d | Wallet=%d->%d",
		totalRequests, workerCount, totalElapsed, rps, p50, p95, p99, maxLat,
		status200.Load(), status409.Load(), status422.Load(), initialBalance, finalBalance)
}

func TestDeterministicReplayFuzzHarness(t *testing.T) {
	endpoint, engine, cleanup := resolveTestTarget(t)
	defer cleanup()

	client := &http.Client{Timeout: 5 * time.Second}
	initialBalance := engine.GetWalletBalance("player_jack_test")
	rng := rand.New(rand.NewSource(20261026))

	const fuzzIterations = 500
	var rejectedCount int

	for i := 0; i < fuzzIterations; i++ {
		if i%5 == 0 {
			// Raw random / corrupted byte fuzzing
			buf := make([]byte, rng.Intn(512))
			_, _ = rng.Read(buf)
			status, _, _, err := postReplayJSON(client, endpoint, buf)
			if err != nil {
				t.Fatalf("fuzz iter %d transport error: %v", i, err)
			}
			if status >= 500 {
				t.Fatalf("fuzz iter %d caused server 5xx panic: status=%d", i, status)
			}
			rejectedCount++
			continue
		}

		// Structured adversarial mutation fuzzing
		fuzzed := ReplayVerifyRequest{
			RunID:               fmt.Sprintf("fuzz_run_%d", i),
			IdempotencyKey:      fmt.Sprintf("fuzz_idem_%d", i),
			PlayerID:            "player_jack_test",
			ConvoyID:            "convoy_jack_01",
			OriginHubID:         "hydro_haven",
			DestinationHubID:    "aether_spire",
			Seed:                rng.Int63(),
			TotalTicks:          rng.Intn(1200) - 200,
			ScrapEarned:         rng.Int63n(100000) - 25000,
			CoolingRodsConsumed: rng.Intn(40) - 10,
			DistanceTraveledM:   (rng.Float64() - 0.2) * 5000.0,
			Actions: []ReplayAction{
				{
					Tick:            rng.Intn(800) - 100,
					ActionType:      "FUZZ_OP",
					ShieldsWatts:    rng.Intn(160) - 40,
					ArtilleryWatts:  rng.Intn(160) - 40,
					ThrustersWatts:  rng.Intn(160) - 40,
					OverclockActive: rng.Intn(2) == 1,
					ScrapDelta:      rng.Int63n(10000) - 2000,
				},
			},
			FinalStateHash:  fmt.Sprintf("%064x", rng.Uint64()),
			SignatureScheme: "HMAC-SHA256",
			Signature:       fmt.Sprintf("%064x", rng.Uint64()),
		}
		raw, _ := json.Marshal(fuzzed)
		status, parsed, _, err := postReplayJSON(client, endpoint, raw)
		if err != nil {
			t.Fatalf("fuzz iter %d error: %v", i, err)
		}
		if status >= 500 {
			t.Fatalf("fuzz iter %d triggered HTTP %d", i, status)
		}
		if parsed.Verified || parsed.LedgerCredited {
			t.Fatalf("fuzz iter %d falsely credited unsigned/fuzzed payload!", i)
		}
		rejectedCount++
	}

	if engine.GetWalletBalance("player_jack_test") != initialBalance {
		t.Fatalf("Fuzz harness mutated player_jack_test wallet balance!")
	}
	t.Logf("[PASS] %d/500 Adversarial Fuzz Payloads rejected cleanly with zero 5xx panics and zero unauthorized wallet mutations",
		rejectedCount)
}
