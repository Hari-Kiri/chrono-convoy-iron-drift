package com.chronoconvoy.qa;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Deterministic Oasis Hub Dynamic Arbitrage & Reactor Cooling Rod Sink Economy Engine.
 * Enforces anti-exploit and conservation laws across all trading, crafting, and transit loops:
 * - Bid/Ask spread (Ask > Bid) + ceiling-rounded transaction tax on every buy/sell order
 * - Dynamic market depth slippage (buying raises ask, selling depresses bid)
 * - Strict protection against int64 overflow, negative/zero quantity exploits, and dust rounding
 * - Mandatory fuel + cooling rod sink deduction on inter-Oasis transit legs
 */
public final class OasisEconomyEngine {

    public enum Commodity {
        IRON_ORE(100L),
        CRUDE_FUEL(140L),
        REFINED_ALLOY(320L),
        COOLING_RODS(150L),
        ARTILLERY_SHELLS(210L),
        CHRONO_CRYSTALS(850L);

        private final long baseMidPrice;

        Commodity(long baseMidPrice) {
            this.baseMidPrice = baseMidPrice;
        }

        public long getBaseMidPrice() {
            return baseMidPrice;
        }
    }

    public enum OasisHub {
        OASIS_ALPHA_RUST_GATE(1.00, 1.00),
        OASIS_BETA_SLAG_BASIN(1.05, 0.96),
        OASIS_GAMMA_GLASS_SPIRE(1.08, 0.94);

        private final double industrialModifier;
        private final double rawResourceModifier;

        OasisHub(double industrialModifier, double rawResourceModifier) {
            this.industrialModifier = industrialModifier;
            this.rawResourceModifier = rawResourceModifier;
        }
    }

    public static final long MAX_SAFE_TRADE_QUANTITY = 10_000_000L;
    public static final int BASE_SPREAD_BPS = 400; // 4.00% half-spread each side -> Ask is +4%, Bid is -4%
    public static final int TRANSACTION_TAX_BPS = 250; // 2.50% Oasis syndicate ledger tax (ceiling rounded)
    public static final long CRAFTING_FEE_PER_BATCH = 35L;
    public static final long TRANSIT_SINK_CREDITS = 250L; // Mandatory fuel + reactor cooling rod wear per transit leg

    private long credits;
    private OasisHub currentHub;
    private final EnumMap<Commodity, Long> inventory = new EnumMap<>(Commodity.class);
    private final EnumMap<OasisHub, EnumMap<Commodity, Long>> hubNetPressure = new EnumMap<>(OasisHub.class);
    private long totalTaxBurned = 0L;
    private long totalSinkBurned = 0L;

    public OasisEconomyEngine(long initialCredits, OasisHub startingHub) {
        if (initialCredits < 0L) {
            throw new IllegalArgumentException("Initial credits cannot be negative: " + initialCredits);
        }
        if (startingHub == null) {
            throw new IllegalArgumentException("Starting OasisHub cannot be null");
        }
        this.credits = initialCredits;
        this.currentHub = startingHub;
        for (Commodity c : Commodity.values()) {
            inventory.put(c, 0L);
        }
        for (OasisHub hub : OasisHub.values()) {
            EnumMap<Commodity, Long> pressure = new EnumMap<>(Commodity.class);
            for (Commodity c : Commodity.values()) {
                pressure.put(c, 0L);
            }
            hubNetPressure.put(hub, pressure);
        }
    }

    public synchronized long quoteUnitAskPrice(OasisHub hub, Commodity commodity, long quantity) {
        validateHubAndCommodity(hub, commodity);
        validatePositiveQuantity(quantity);

        long baseMid = computeRegionalMidPrice(hub, commodity);
        long netBought = Math.max(0L, hubNetPressure.get(hub).get(commodity));
        // Slippage increases unit ask as buy volume accumulates
        long slippageBps = Math.min(5_000L, (netBought + (quantity / 2L)) / 10L);
        long totalMarkupBps = Math.addExact((long) BASE_SPREAD_BPS, slippageBps);
        // Ceiling integer division guarantees Ask > Mid
        return Math.max(baseMid + 1L, ceilDiv(Math.multiplyExact(baseMid, 10_000L + totalMarkupBps), 10_000L));
    }

    public synchronized long quoteUnitBidPrice(OasisHub hub, Commodity commodity, long quantity) {
        validateHubAndCommodity(hub, commodity);
        validatePositiveQuantity(quantity);

        long baseMid = computeRegionalMidPrice(hub, commodity);
        long netSold = Math.max(0L, -hubNetPressure.get(hub).get(commodity));
        long slippageBps = Math.min(5_000L, (netSold + (quantity / 2L)) / 10L);
        long totalMarkdownBps = Math.min(9_000L, Math.addExact((long) BASE_SPREAD_BPS, slippageBps));
        // Floor integer division guarantees Bid < Mid < Ask
        long rawBid = Math.multiplyExact(baseMid, 10_000L - totalMarkdownBps) / 10_000L;
        return Math.max(1L, Math.min(baseMid - 1L, rawBid));
    }

    private long computeRegionalMidPrice(OasisHub hub, Commodity commodity) {
        double mod = switch (commodity) {
            case IRON_ORE, CRUDE_FUEL -> hub.rawResourceModifier;
            case REFINED_ALLOY, ARTILLERY_SHELLS, COOLING_RODS, CHRONO_CRYSTALS -> hub.industrialModifier;
        };
        return Math.max(10L, Math.round(commodity.getBaseMidPrice() * mod));
    }

    /**
     * Buys quantity of commodity at the current Oasis hub, charging gross ask + ceiling-rounded syndicate tax.
     */
    public synchronized long buy(Commodity commodity, long quantity) {
        validateHubAndCommodity(currentHub, commodity);
        validatePositiveQuantity(quantity);

        long unitAsk = quoteUnitAskPrice(currentHub, commodity, quantity);
        long grossCost = Math.multiplyExact(unitAsk, quantity);
        long tax = Math.max(1L, ceilDiv(Math.multiplyExact(grossCost, TRANSACTION_TAX_BPS), 10_000L));
        long totalCost = Math.addExact(grossCost, tax);

        if (totalCost > credits) {
            throw new IllegalStateException("Insufficient credits: need " + totalCost + ", have " + credits);
        }

        long currentQty = inventory.get(commodity);
        long newQty = Math.addExact(currentQty, quantity);

        credits -= totalCost;
        inventory.put(commodity, newQty);
        totalTaxBurned = Math.addExact(totalTaxBurned, tax);

        EnumMap<Commodity, Long> pressure = hubNetPressure.get(currentHub);
        pressure.put(commodity, Math.addExact(pressure.get(commodity), quantity));
        return totalCost;
    }

    /**
     * Sells quantity of commodity at the current Oasis hub, paying gross bid minus ceiling-rounded syndicate tax.
     */
    public synchronized long sell(Commodity commodity, long quantity) {
        validateHubAndCommodity(currentHub, commodity);
        validatePositiveQuantity(quantity);

        long owned = inventory.get(commodity);
        if (quantity > owned) {
            throw new IllegalStateException("Insufficient inventory of " + commodity + ": trying to sell " + quantity + ", own " + owned);
        }

        long unitBid = quoteUnitBidPrice(currentHub, commodity, quantity);
        long grossProceeds = Math.multiplyExact(unitBid, quantity);
        long tax = Math.max(1L, ceilDiv(Math.multiplyExact(grossProceeds, TRANSACTION_TAX_BPS), 10_000L));
        long netProceeds = Math.max(0L, grossProceeds - tax);

        credits = Math.addExact(credits, netProceeds);
        inventory.put(commodity, owned - quantity);
        totalTaxBurned = Math.addExact(totalTaxBurned, tax);

        EnumMap<Commodity, Long> pressure = hubNetPressure.get(currentHub);
        pressure.put(commodity, Math.subtractExact(pressure.get(commodity), quantity));
        return netProceeds;
    }

    /**
     * Smelts 2 IRON_ORE + 1 CRUDE_FUEL + CRAFTING_FEE_PER_BATCH -> 1 REFINED_ALLOY per batch.
     * Designed so that buying inputs at Ask, crafting, and selling output at Bid cannot create a free money loop.
     */
    public synchronized void craftRefinedAlloy(long batches) {
        validatePositiveQuantity(batches);
        long requiredOre = Math.multiplyExact(2L, batches);
        long requiredFuel = batches;
        long totalFee = Math.multiplyExact(CRAFTING_FEE_PER_BATCH, batches);

        long ownedOre = inventory.get(Commodity.IRON_ORE);
        long ownedFuel = inventory.get(Commodity.CRUDE_FUEL);
        if (ownedOre < requiredOre || ownedFuel < requiredFuel) {
            throw new IllegalStateException("Insufficient crafting materials for " + batches + " batches of REFINED_ALLOY");
        }
        if (credits < totalFee) {
            throw new IllegalStateException("Insufficient credits for crafting fee: " + totalFee);
        }

        credits -= totalFee;
        totalSinkBurned = Math.addExact(totalSinkBurned, totalFee);
        inventory.put(Commodity.IRON_ORE, ownedOre - requiredOre);
        inventory.put(Commodity.CRUDE_FUEL, ownedFuel - requiredFuel);
        inventory.put(Commodity.REFINED_ALLOY, Math.addExact(inventory.get(Commodity.REFINED_ALLOY), batches));
    }

    /**
     * Transits convoy between Oasis hubs, burning mandatory fuel + cooling-rod maintenance credits.
     */
    public synchronized void transitToHub(OasisHub destination) {
        if (destination == null) {
            throw new IllegalArgumentException("Destination hub cannot be null");
        }
        if (destination == currentHub) {
            return;
        }
        if (credits < TRANSIT_SINK_CREDITS) {
            throw new IllegalStateException("Insufficient credits for inter-Oasis transit fuel & cooling rod sink (" + TRANSIT_SINK_CREDITS + ")");
        }
        credits -= TRANSIT_SINK_CREDITS;
        totalSinkBurned = Math.addExact(totalSinkBurned, TRANSIT_SINK_CREDITS);
        currentHub = destination;
    }

    private static void validateHubAndCommodity(OasisHub hub, Commodity commodity) {
        if (hub == null || commodity == null) {
            throw new IllegalArgumentException("OasisHub and Commodity must not be null");
        }
    }

    private static void validatePositiveQuantity(long quantity) {
        if (quantity <= 0L) {
            throw new IllegalArgumentException("Trade/craft quantity must be strictly positive (> 0), got: " + quantity);
        }
        if (quantity > MAX_SAFE_TRADE_QUANTITY) {
            throw new IllegalArgumentException("Quantity " + quantity + " exceeds max single-order ceiling (" + MAX_SAFE_TRADE_QUANTITY + ")");
        }
    }

    private static long ceilDiv(long x, long y) {
        return (x + y - 1L) / y;
    }

    public synchronized long getCredits() {
        return credits;
    }

    public synchronized OasisHub getCurrentHub() {
        return currentHub;
    }

    public synchronized long getInventory(Commodity commodity) {
        return inventory.getOrDefault(commodity, 0L);
    }

    public synchronized Map<Commodity, Long> getInventorySnapshot() {
        return Collections.unmodifiableMap(new EnumMap<>(inventory));
    }

    public synchronized long getTotalTaxBurned() {
        return totalTaxBurned;
    }

    public synchronized long getTotalSinkBurned() {
        return totalSinkBurned;
    }
}
