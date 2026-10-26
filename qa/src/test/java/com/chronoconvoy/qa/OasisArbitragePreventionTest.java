package com.chronoconvoy.qa;

import com.chronoconvoy.qa.OasisEconomyEngine.Commodity;
import com.chronoconvoy.qa.OasisEconomyEngine.OasisHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QA-01C: Oasis Hub Arbitrage Exploit Prevention & Ledger Conservation Suite")
class OasisArbitragePreventionTest {

    @Test
    @DisplayName("1. Bid/Ask Spread Invariant: Unit Ask is strictly greater than Unit Bid across all hubs and commodities")
    void testBidStrictlyLessThanAskEverywhere() {
        OasisEconomyEngine engine = new OasisEconomyEngine(1_000_000L, OasisHub.OASIS_ALPHA_RUST_GATE);
        long[] quantities = new long[] {1L, 10L, 250L, 5_000L, 100_000L};

        for (OasisHub hub : OasisHub.values()) {
            for (Commodity c : Commodity.values()) {
                for (long q : quantities) {
                    long ask = engine.quoteUnitAskPrice(hub, c, q);
                    long bid = engine.quoteUnitBidPrice(hub, c, q);
                    assertTrue(ask > bid,
                            "Spread inversion detected at " + hub + " for " + c + " (qty=" + q + "): ask=" + ask + ", bid=" + bid);
                }
            }
        }
    }

    @Test
    @DisplayName("2. Same-Hub Round-Trip Buy/Sell Loop yields strictly negative profit (finalCredits < initialCredits)")
    void testSameHubRoundTripArbitrageIsStrictlyLossMaking() {
        long initialCredits = 50_000_000L;
        long[] batchSizes = new long[] {1L, 2L, 7L, 50L, 1_000L, 25_000L};

        for (OasisHub hub : OasisHub.values()) {
            for (Commodity c : Commodity.values()) {
                for (long qty : batchSizes) {
                    OasisEconomyEngine engine = new OasisEconomyEngine(initialCredits, hub);
                    engine.buy(c, qty);
                    engine.sell(c, qty);

                    assertEquals(0L, engine.getInventory(c), "Round-trip inventory must return to zero");
                    assertTrue(engine.getCredits() < initialCredits,
                            "Zero-risk round-trip trade must lose credits to spread + tax at " + hub + " for " + c + " qty=" + qty);
                    assertTrue(engine.getTotalTaxBurned() >= 2L, "Every buy and sell must burn >= 1 credit of tax");
                }
            }
        }
    }

    @Test
    @DisplayName("3. Micro-Lot (1-Unit) Split-Order Dust Exploit cannot outperform Bulk Order or generate profit")
    void testMicroLotSplitOrderDustExploitPrevention() {
        long initialCredits = 100_000L;
        OasisEconomyEngine microTrader = new OasisEconomyEngine(initialCredits, OasisHub.OASIS_ALPHA_RUST_GATE);

        for (int i = 0; i < 200; i++) {
            microTrader.buy(Commodity.IRON_ORE, 1L);
            microTrader.sell(Commodity.IRON_ORE, 1L);
        }

        assertTrue(microTrader.getCredits() < initialCredits,
                "200 micro-lot round trips must strictly reduce credits");
        assertTrue(microTrader.getTotalTaxBurned() >= 400L,
                "Ceiling tax rounding must charge at least 1 credit per micro-transaction");
    }

    @Test
    @DisplayName("4. Closed-Loop Crafting Arbitrage (Buy Ore + Fuel -> Craft Refined Alloy -> Sell Alloy) yields finalCredits <= initialCredits")
    void testClosedLoopCraftingArbitragePrevention() {
        long initialCredits = 1_000_000L;
        long[] batchCounts = new long[] {1L, 5L, 25L, 100L, 500L};

        for (OasisHub hub : OasisHub.values()) {
            for (long batches : batchCounts) {
                OasisEconomyEngine engine = new OasisEconomyEngine(initialCredits, hub);
                engine.buy(Commodity.IRON_ORE, batches * 2L);
                engine.buy(Commodity.CRUDE_FUEL, batches);
                engine.craftRefinedAlloy(batches);
                engine.sell(Commodity.REFINED_ALLOY, batches);

                assertEquals(0L, engine.getInventory(Commodity.IRON_ORE));
                assertEquals(0L, engine.getInventory(Commodity.CRUDE_FUEL));
                assertEquals(0L, engine.getInventory(Commodity.REFINED_ALLOY));
                assertTrue(engine.getCredits() <= initialCredits,
                        "Closed-loop station crafting must not print free money at " + hub + " (batches=" + batches
                                + ", final=" + engine.getCredits() + ", initial=" + initialCredits + ")");
            }
        }
    }

    @Test
    @DisplayName("5. Multi-Hub Triangular Transit Loop with Small Cargo loses to Transit Fuel/Cooling-Rod Sink & Slippage")
    void testMultiHubTriangularCycleSinkEnforcement() {
        long initialCredits = 10_000L;
        OasisEconomyEngine engine = new OasisEconomyEngine(initialCredits, OasisHub.OASIS_ALPHA_RUST_GATE);

        // Attempt rapid empty or small-lot hub hopping loop Alpha -> Beta -> Gamma -> Alpha
        engine.buy(Commodity.IRON_ORE, 10L);
        engine.transitToHub(OasisHub.OASIS_BETA_SLAG_BASIN);
        engine.sell(Commodity.IRON_ORE, 10L);

        engine.buy(Commodity.COOLING_RODS, 10L);
        engine.transitToHub(OasisHub.OASIS_GAMMA_GLASS_SPIRE);
        engine.sell(Commodity.COOLING_RODS, 10L);

        engine.transitToHub(OasisHub.OASIS_ALPHA_RUST_GATE);

        assertTrue(engine.getCredits() <= initialCredits,
                "Triangular transit loop must not generate free credits (final=" + engine.getCredits() + ")");
        assertEquals(3L * OasisEconomyEngine.TRANSIT_SINK_CREDITS, engine.getTotalSinkBurned());
    }

    @Test
    @DisplayName("6. Boundary Input Guard: Rejects negative, zero, and overflow trade/craft quantities")
    void testRejectNegativeZeroAndOverflowQuantities() {
        OasisEconomyEngine engine = new OasisEconomyEngine(500_000L, OasisHub.OASIS_ALPHA_RUST_GATE);

        assertThrows(IllegalArgumentException.class, () -> engine.buy(Commodity.IRON_ORE, 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.buy(Commodity.IRON_ORE, -50L));
        assertThrows(IllegalArgumentException.class, () -> engine.buy(Commodity.IRON_ORE, Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> engine.buy(Commodity.IRON_ORE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> engine.buy(Commodity.IRON_ORE, OasisEconomyEngine.MAX_SAFE_TRADE_QUANTITY + 1L));

        assertThrows(IllegalArgumentException.class, () -> engine.sell(Commodity.IRON_ORE, 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.sell(Commodity.IRON_ORE, -1L));
        assertThrows(IllegalArgumentException.class, () -> engine.craftRefinedAlloy(0L));
        assertThrows(IllegalArgumentException.class, () -> engine.craftRefinedAlloy(-10L));
    }

    @Test
    @DisplayName("7. Inventory & Credit Solvency Guard: Rejects short-selling unowned cargo or spending unowned credits")
    void testRejectUnownedInventorySaleAndCreditOverdraft() {
        OasisEconomyEngine engine = new OasisEconomyEngine(500L, OasisHub.OASIS_ALPHA_RUST_GATE);

        // Cannot sell cargo when inventory is 0
        assertThrows(IllegalStateException.class, () -> engine.sell(Commodity.CHRONO_CRYSTALS, 1L));
        // Cannot buy 10 Chrono Crystals (~8840 credits) with only 500 credits
        assertThrows(IllegalStateException.class, () -> engine.buy(Commodity.CHRONO_CRYSTALS, 10L));

        assertEquals(500L, engine.getCredits());
        assertEquals(0L, engine.getInventory(Commodity.CHRONO_CRYSTALS));
    }

    @Test
    @DisplayName("8. 1,000-Iteration Randomized Fuzz Trading Audit guarantees zero closed-loop arbitrage across all states")
    void testRandomized1000IterationFuzzClosedLoopAudit() {
        Random rng = new Random(20261026L);
        Commodity[] commodities = Commodity.values();
        OasisHub[] hubs = OasisHub.values();

        for (int iter = 0; iter < 1_000; iter++) {
            OasisHub hub = hubs[rng.nextInt(hubs.length)];
            OasisEconomyEngine engine = new OasisEconomyEngine(5_000_000L, hub);
            long startCredits = engine.getCredits();

            int steps = 1 + rng.nextInt(6);
            for (int s = 0; s < steps; s++) {
                Commodity c = commodities[rng.nextInt(commodities.length)];
                long qty = 1L + rng.nextInt(250);
                engine.buy(c, qty);
            }
            // Liquidate all purchased inventory at the hub to close the loop
            for (Commodity c : commodities) {
                long owned = engine.getInventory(c);
                if (owned > 0L) {
                    engine.sell(c, owned);
                }
            }

            long endCredits = engine.getCredits();
            assertTrue(endCredits < startCredits,
                    "Fuzz iteration " + iter + " violated non-arbitrage invariant: end=" + endCredits + " >= start=" + startCredits);
        }
    }
}
