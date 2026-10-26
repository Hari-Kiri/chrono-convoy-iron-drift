package ledger

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/chrono-convoy/backend/internal/replay"
)

func openTestRepo(t *testing.T) *PostgresLedgerRepository {
	t.Helper()
	repo, err := NewPostgresLedgerRepository("postgres://box@localhost:5432/chrono_convoy?sslmode=disable")
	if err != nil {
		t.Fatalf("failed to connect to local PostgreSQL chrono_convoy: %v", err)
	}
	t.Cleanup(func() {
		_ = repo.Close()
	})
	return repo
}

func TestPostgresLedger_LoadoutAndABAC(t *testing.T) {
	ctx := context.Background()
	repo := openTestRepo(t)

	// Read seeded convoy loadout for player_cmdr_riki
	loadout, err := repo.GetConvoyLoadout(ctx, "player_cmdr_riki", "convoy_iron_drift_01")
	if err != nil {
		t.Fatalf("GetConvoyLoadout failed: %v", err)
	}
	if loadout.ConvoyID != "convoy_iron_drift_01" || loadout.PlayerID != "player_cmdr_riki" {
		t.Fatalf("unexpected loadout: %+v", loadout)
	}
	if loadout.Wallet == nil {
		t.Fatalf("expected non-nil player wallet in loadout response")
	}

	// ABAC / IDOR check: another player cannot read or modify player_cmdr_riki's convoy
	if _, err := repo.GetConvoyLoadout(ctx, "player_intruder", "convoy_iron_drift_01"); !errors.Is(err, ErrForbiddenConvoyOwner) {
		t.Fatalf("expected ErrForbiddenConvoyOwner on cross-player read, got %v", err)
	}

	// Reject invalid carriage idle watt allocation (> max_reactor_watts)
	_, err = repo.UpsertConvoyLoadout(ctx, "player_cmdr_riki", &UpdateLoadoutRequest{
		ConvoyID:        "convoy_iron_drift_01",
		PlayerID:        "player_cmdr_riki",
		MaxReactorWatts: 100,
		Carriages: []CarriageSlot{
			{Slot: 1, BlueprintID: "gatling_turret_car", WattDraw: 60, CargoCap: 40},
			{Slot: 2, BlueprintID: "artillery_mortar_car", WattDraw: 60, CargoCap: 50},
		},
	})
	if !errors.Is(err, ErrInvalidLoadoutSpec) {
		t.Fatalf("expected ErrInvalidLoadoutSpec, got %v", err)
	}

	// Valid update of modular carriages & reactor budget
	updated, err := repo.UpsertConvoyLoadout(ctx, "player_cmdr_riki", &UpdateLoadoutRequest{
		ConvoyID:         "convoy_iron_drift_01",
		PlayerID:         "player_cmdr_riki",
		LocomotiveClass:  "iron_drifter_mk1",
		MaxReactorWatts:  100,
		MaxCargoCapacity: 250,
		CoolingRodBay:    12,
		Carriages: []CarriageSlot{
			{Slot: 1, BlueprintID: "gatling_turret_car", WattDraw: 10, CargoCap: 40},
			{Slot: 2, BlueprintID: "shield_dome_car", WattDraw: 15, CargoCap: 30},
			{Slot: 3, BlueprintID: "cargo_smelter_car", WattDraw: 10, CargoCap: 180},
		},
	})
	if err != nil {
		t.Fatalf("UpsertConvoyLoadout failed: %v", err)
	}
	if len(updated.Carriages) != 3 {
		t.Fatalf("unexpected updated loadout carriages: %+v", updated)
	}
}

func TestPostgresLedger_IdempotentTradeAndSettlement(t *testing.T) {
	ctx := context.Background()
	repo := openTestRepo(t)

	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	playerID := "player_test_" + suffix
	convoyID := "convoy_test_" + suffix

	// Create fresh test player & convoy via UpsertConvoyLoadout (seeds 2500 iron_scrip)
	_, err := repo.UpsertConvoyLoadout(ctx, playerID, &UpdateLoadoutRequest{
		ConvoyID:         convoyID,
		PlayerID:         playerID,
		MaxReactorWatts:  100,
		MaxCargoCapacity: 200,
		CoolingRodBay:    12,
		Carriages: []CarriageSlot{
			{Slot: 1, BlueprintID: "cargo_smelter_car", WattDraw: 10, CargoCap: 200},
		},
	})
	if err != nil {
		t.Fatalf("failed to create test convoy: %v", err)
	}

	// Execute a BUY trade with a fixed idempotency key across 8 concurrent goroutines
	idemBuyKey := "idem_buy_" + suffix
	var wg sync.WaitGroup
	results := make([]*LedgerEntry, 8)
	errs := make([]error, 8)

	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for attempt := 0; attempt < 6; attempt++ {
				entry, txErr := repo.ExecuteIdempotentTrade(ctx, playerID, &TradeLedgerRequest{
					IdempotencyKey: idemBuyKey,
					PlayerID:       playerID,
					ConvoyID:       convoyID,
					HubID:          "hydro_haven",
					CommodityID:    "purified_water",
					Action:         "BUY",
					Quantity:       20,
					UnitPriceScrip: 40, // total cost = 800 scrip
				})
				if txErr == nil {
					results[idx] = entry
					errs[idx] = nil
					return
				}
				errs[idx] = txErr
				time.Sleep(5 * time.Millisecond)
			}
		}(i)
	}
	wg.Wait()

	for i, txErr := range errs {
		if txErr != nil {
			t.Fatalf("goroutine %d failed ExecuteIdempotentTrade: %v", i, txErr)
		}
		if results[i].ScripBalanceAfter != 1700 { // 2500 initial - 800 cost = 1700
			t.Fatalf("goroutine %d expected scrip_balance_after=1700, got %d", i, results[i].ScripBalanceAfter)
		}
	}

	// Verify repeat call returns IdempotentReplay = true without charging again
	replayEntry, err := repo.ExecuteIdempotentTrade(ctx, playerID, &TradeLedgerRequest{
		IdempotencyKey: idemBuyKey,
		PlayerID:       playerID,
		ConvoyID:       convoyID,
		HubID:          "hydro_haven",
		CommodityID:    "purified_water",
		Action:         "BUY",
		Quantity:       20,
		UnitPriceScrip: 40,
	})
	if err != nil {
		t.Fatalf("replay ExecuteIdempotentTrade failed: %v", err)
	}
	if !replayEntry.IdempotentReplay || replayEntry.ScripBalanceAfter != 1700 {
		t.Fatalf("expected IdempotentReplay=true with balance 1700, got %+v", replayEntry)
	}

	// Deliver 10 units of purified_water at iron_refinery for 55 scrip/unit (+550 scrip -> 2250)
	deliverEntry, err := repo.ExecuteIdempotentTrade(ctx, playerID, &TradeLedgerRequest{
		IdempotencyKey: "idem_deliver_" + suffix,
		PlayerID:       playerID,
		ConvoyID:       convoyID,
		HubID:          "iron_refinery",
		CommodityID:    "purified_water",
		Action:         "DELIVER",
		Quantity:       10,
		UnitPriceScrip: 55,
	})
	if err != nil {
		t.Fatalf("DELIVER trade failed: %v", err)
	}
	if deliverEntry.ScripBalanceAfter != 2250 || deliverEntry.CargoQtyAfter != 10 {
		t.Fatalf("expected scrip balance 2250 & cargo 10 after delivery, got %+v", deliverEntry)
	}

	// Verify RecordVerifiedRunAndSettle and its idempotency
	runRes := &replay.VerifyRunResult{
		Verified:            true,
		RunID:               "run_" + suffix,
		IdempotencyKey:      "idem_run_" + suffix,
		PlayerID:            playerID,
		ConvoyID:            convoyID,
		OriginHubID:         "hydro_haven",
		DestinationHubID:    "iron_refinery",
		Seed:                123456789,
		ActionCount:         5,
		ScrapEarned:         180,
		CoolingRodsConsumed: 2,
		DistanceTraveledM:   950.5,
		FinalStateHash:      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		SignatureScheme:     "HMAC-SHA256",
	}
	settle1, err := repo.RecordVerifiedRunAndSettle(ctx, playerID, runRes, 100)
	if err != nil {
		t.Fatalf("RecordVerifiedRunAndSettle failed: %v", err)
	}
	if settle1.ScripBalanceAfter != 2530 || settle1.CargoQtyAfter != 10 {
		t.Fatalf("unexpected settled balances: %+v", settle1)
	}

	// Duplicate settlement with same IdempotencyKey must be a no-op replay
	settle2, err := repo.RecordVerifiedRunAndSettle(ctx, playerID, runRes, 100)
	if err != nil {
		t.Fatalf("second RecordVerifiedRunAndSettle failed: %v", err)
	}
	if !settle2.IdempotentReplay || settle2.ScripBalanceAfter != 2530 {
		t.Fatalf("expected idempotent replay on duplicate run settlement, got %+v", settle2)
	}
}
