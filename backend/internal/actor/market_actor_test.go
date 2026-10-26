package actor

import (
	"context"
	"sync"
	"testing"
	"time"
)

func TestOasisMarketActor_InitialStateAndAliases(t *testing.T) {
	ctx := context.Background()
	sup := NewRegionMarketSupervisor(ctx, 128, 60*time.Second)
	defer sup.Shutdown()

	snaps, err := sup.GetAllHubSnapshots(ctx)
	if err != nil {
		t.Fatalf("GetAllHubSnapshots failed: %v", err)
	}
	if len(snaps) != 3 {
		t.Fatalf("expected 3 Oasis Hubs, got %d", len(snaps))
	}

	for _, snap := range snaps {
		if len(snap.Commodities) != 5 {
			t.Fatalf("hub %s expected 5 commodities, got %d", snap.HubID, len(snap.Commodities))
		}
		for cid, q := range snap.Commodities {
			if q.AskPriceScrip <= q.BidPriceScrip {
				t.Errorf("hub %s commodity %s invalid spread: ask=%d <= bid=%d", snap.HubID, cid, q.AskPriceScrip, q.BidPriceScrip)
			}
		}
	}

	// Verify aliases (oasis_alpha -> hydro_haven, OASIS_BETA_SLAG_BASIN -> iron_refinery, oasis_gamma -> aether_spire)
	alphaSnap, err := sup.GetHubSnapshot(ctx, "oasis_alpha")
	if err != nil || alphaSnap.HubID != "hydro_haven" {
		t.Fatalf("expected oasis_alpha to resolve to hydro_haven, got %+v, err=%v", alphaSnap, err)
	}
	betaSnap, err := sup.GetHubSnapshot(ctx, "OASIS_BETA_SLAG_BASIN")
	if err != nil || betaSnap.HubID != "iron_refinery" {
		t.Fatalf("expected OASIS_BETA_SLAG_BASIN to resolve to iron_refinery, got %+v, err=%v", betaSnap, err)
	}
}

func TestOasisMarketActor_TradeAnd60sRecalcTick(t *testing.T) {
	ctx := context.Background()
	sup := NewRegionMarketSupervisor(ctx, 128, 50*time.Millisecond)
	defer sup.Shutdown()

	before, err := sup.GetHubSnapshot(ctx, "iron_refinery")
	if err != nil {
		t.Fatalf("GetHubSnapshot failed: %v", err)
	}
	waterBefore := before.Commodities["purified_water"]

	// Player delivers 100 units of purified_water to iron_refinery -> supply increases, mid price drops
	deliverRes, err := sup.ExecuteTrade(ctx, "iron_refinery", "purified_water", TradeActionDeliver, 100)
	if err != nil {
		t.Fatalf("ExecuteTrade DELIVER failed: %v", err)
	}
	if deliverRes.NetScripDelta <= 0 {
		t.Fatalf("expected positive scrip payout for delivery, got %d", deliverRes.NetScripDelta)
	}
	if deliverRes.UpdatedQuote.Supply != waterBefore.Supply+100 {
		t.Fatalf("expected supply %d, got %d", waterBefore.Supply+100, deliverRes.UpdatedQuote.Supply)
	}
	if deliverRes.UpdatedQuote.MidPrice >= waterBefore.MidPrice {
		t.Fatalf("expected mid price to decrease after large delivery: before=%.2f, after=%.2f",
			waterBefore.MidPrice, deliverRes.UpdatedQuote.MidPrice)
	}

	// Player buys 40 units of cooling_rods -> supply decreases, mid price increases
	rodsBefore := before.Commodities["cooling_rods"]
	buyRes, err := sup.ExecuteTrade(ctx, "iron_refinery", "cooling_rods", TradeActionBuy, 40)
	if err != nil {
		t.Fatalf("ExecuteTrade BUY failed: %v", err)
	}
	if buyRes.NetScripDelta >= 0 {
		t.Fatalf("expected negative scrip delta for purchase, got %d", buyRes.NetScripDelta)
	}
	if buyRes.UpdatedQuote.MidPrice <= rodsBefore.MidPrice {
		t.Fatalf("expected cooling_rods mid price to increase after purchase: before=%.2f, after=%.2f",
			rodsBefore.MidPrice, buyRes.UpdatedQuote.MidPrice)
	}

	// Trigger manual 60s recalculation tick and verify TickEpoch advances across all 3 hubs
	tickSnaps, err := sup.TriggerManualRecalcTick(ctx, time.Now().UTC().Add(60*time.Second))
	if err != nil {
		t.Fatalf("TriggerManualRecalcTick failed: %v", err)
	}
	for _, s := range tickSnaps {
		if s.TickEpoch < 2 {
			t.Errorf("expected TickEpoch >= 2 after manual recalc tick on hub %s, got %d", s.HubID, s.TickEpoch)
		}
	}
}

func TestRegionMarketSupervisor_OneForOneFaultRecoveryAndConcurrency(t *testing.T) {
	ctx := context.Background()
	sup := NewRegionMarketSupervisor(ctx, 256, 60*time.Second)
	defer sup.Shutdown()

	// Mutate iron_refinery state before injecting a fault into hydro_haven
	_, err := sup.ExecuteTrade(ctx, "iron_refinery", "scrap_alloy", TradeActionDeliver, 50)
	if err != nil {
		t.Fatalf("ExecuteTrade on iron_refinery failed: %v", err)
	}

	// Inject panic into hydro_haven
	if err := sup.InjectFault("hydro_haven", "simulated cosmic ray corruption"); err != nil {
		t.Fatalf("InjectFault failed: %v", err)
	}

	// Wait for supervisor restart event
	select {
	case restartedHub := <-sup.RestartEvents():
		if restartedHub != "hydro_haven" {
			t.Fatalf("expected hydro_haven restart event, got %s", restartedHub)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for One-For-One supervisor restart of hydro_haven")
	}

	// Wait briefly for child goroutine to re-enter runLoop and verify it responds
	time.Sleep(15 * time.Millisecond)
	alphaAfter, err := sup.GetHubSnapshot(ctx, "hydro_haven")
	if err != nil {
		t.Fatalf("GetHubSnapshot after restart failed: %v", err)
	}
	if alphaAfter.RestartCount < 1 {
		t.Fatalf("expected RestartCount >= 1 on hydro_haven, got %d", alphaAfter.RestartCount)
	}

	// Verify iron_refinery state was completely isolated and unaffected by hydro_haven's crash!
	betaAfter, err := sup.GetHubSnapshot(ctx, "iron_refinery")
	if err != nil {
		t.Fatalf("GetHubSnapshot on iron_refinery failed: %v", err)
	}
	if betaAfter.RestartCount != 0 {
		t.Fatalf("expected iron_refinery RestartCount == 0, got %d", betaAfter.RestartCount)
	}
	if betaAfter.Commodities["scrap_alloy"].DeliveriesVolume != 50 {
		t.Fatalf("expected iron_refinery deliveries volume 50 preserved, got %d",
			betaAfter.Commodities["scrap_alloy"].DeliveriesVolume)
	}

	// High-concurrency race detector stress test across all 3 hubs
	var wg sync.WaitGroup
	hubs := []string{"hydro_haven", "iron_refinery", "aether_spire"}
	commodities := []string{"purified_water", "scrap_alloy", "crude_promethium", "reactor_cells", "cooling_rods"}

	for i := 0; i < 60; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			hub := hubs[workerID%len(hubs)]
			comm := commodities[workerID%len(commodities)]
			if workerID%2 == 0 {
				_, _ = sup.ExecuteTrade(ctx, hub, comm, TradeActionDeliver, 5)
			} else {
				_, _ = sup.ExecuteTrade(ctx, hub, comm, TradeActionBuy, 2)
			}
			_, _ = sup.GetHubSnapshot(ctx, hub)
		}(i)
	}
	wg.Wait()
}
