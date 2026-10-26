package com.chronoconvoy.qa;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Deterministic Reactor Watt Power-Routing Controller and Boundary Validator.
 * Enforces strict conservation of reactor output watts across all convoy subsystems:
 * - Sum of allocated subsystem watts <= maxReactorOutputWatts
 * - Rejects negative allocations, integer/long overflows, NaN/Infinite multipliers, and over-budget mutations
 * - Thread-safe under concurrent engineer deck adjustments and brownout damage events
 */
public final class ReactorPowerController {

    public enum Subsystem {
        PROPULSION_DRIVE(600L),
        ARTILLERY_TURRETS(500L),
        THERMAL_SHIELDS(450L),
        COOLING_RODS_PUMP(300L),
        RADAR_ECM(250L);

        private final long subsystemMaxWatts;

        Subsystem(long subsystemMaxWatts) {
            this.subsystemMaxWatts = subsystemMaxWatts;
        }

        public long getSubsystemMaxWatts() {
            return subsystemMaxWatts;
        }
    }

    public static final long ABSOLUTE_MAX_REACTOR_CAPACITY = 10_000L;

    private long maxReactorOutputWatts;
    private final EnumMap<Subsystem, Long> allocations = new EnumMap<>(Subsystem.class);
    private int coolingRodsInstalled = 2;

    public ReactorPowerController(long maxReactorOutputWatts) {
        if (maxReactorOutputWatts <= 0L || maxReactorOutputWatts > ABSOLUTE_MAX_REACTOR_CAPACITY) {
            throw new IllegalArgumentException(
                    "Invalid maxReactorOutputWatts: " + maxReactorOutputWatts + " (must be in 1.." + ABSOLUTE_MAX_REACTOR_CAPACITY + ")");
        }
        this.maxReactorOutputWatts = maxReactorOutputWatts;
        for (Subsystem s : Subsystem.values()) {
            allocations.put(s, 0L);
        }
    }

    /**
     * Atomically allocates watts to a single subsystem if and only if all invariants hold.
     */
    public synchronized void allocateSubsystemWatts(Subsystem subsystem, long requestedWatts) {
        if (subsystem == null) {
            throw new IllegalArgumentException("Subsystem cannot be null");
        }
        if (requestedWatts < 0L) {
            throw new IllegalArgumentException("Negative watt allocation rejected for " + subsystem + ": " + requestedWatts);
        }
        if (requestedWatts > subsystem.getSubsystemMaxWatts()) {
            throw new IllegalArgumentException(
                    "Requested watts (" + requestedWatts + ") exceeds subsystem cap (" + subsystem.getSubsystemMaxWatts() + ") for " + subsystem);
        }

        long totalOtherSubsystems = 0L;
        for (Map.Entry<Subsystem, Long> entry : allocations.entrySet()) {
            if (entry.getKey() != subsystem) {
                totalOtherSubsystems = Math.addExact(totalOtherSubsystems, entry.getValue());
            }
        }

        long newTotal;
        try {
            newTotal = Math.addExact(totalOtherSubsystems, requestedWatts);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Arithmetic overflow when computing reactor watt sum", ex);
        }

        if (newTotal > maxReactorOutputWatts) {
            throw new IllegalStateException(
                    "Reactor power budget exceeded: requested total " + newTotal + "W > max output " + maxReactorOutputWatts + "W");
        }

        allocations.put(subsystem, requestedWatts);
    }

    /**
     * Atomically applies a full routing profile across multiple subsystems.
     * Either the entire profile succeeds or no state is mutated.
     */
    public synchronized void applyFullRoutingProfile(Map<Subsystem, Long> profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Routing profile cannot be null");
        }
        EnumMap<Subsystem, Long> candidate = new EnumMap<>(Subsystem.class);
        for (Subsystem s : Subsystem.values()) {
            candidate.put(s, 0L);
        }

        long sum = 0L;
        for (Map.Entry<Subsystem, Long> entry : profile.entrySet()) {
            Subsystem s = entry.getKey();
            Long watts = entry.getValue();
            if (s == null || watts == null) {
                throw new IllegalArgumentException("Null subsystem or watt value in routing profile");
            }
            if (watts < 0L) {
                throw new IllegalArgumentException("Negative watt allocation rejected for " + s + ": " + watts);
            }
            if (watts > s.getSubsystemMaxWatts()) {
                throw new IllegalArgumentException("Subsystem cap exceeded for " + s + ": " + watts + " > " + s.getSubsystemMaxWatts());
            }
            try {
                sum = Math.addExact(sum, watts);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Integer overflow in routing profile total watts", ex);
            }
            candidate.put(s, watts);
        }

        if (sum > maxReactorOutputWatts) {
            throw new IllegalStateException("Routing profile sum " + sum + "W exceeds reactor capacity " + maxReactorOutputWatts + "W");
        }

        allocations.putAll(candidate);
    }

    /**
     * Scales a subsystem allocation by a floating-point efficiency or overdrive factor,
     * rejecting NaN, +/-Infinity, and negative multipliers.
     */
    public synchronized long scaleSubsystemWatts(Subsystem subsystem, double factor) {
        if (subsystem == null) {
            throw new IllegalArgumentException("Subsystem cannot be null");
        }
        if (!Double.isFinite(factor) || factor < 0.0d) {
            throw new IllegalArgumentException("Invalid non-finite or negative scaling factor: " + factor);
        }
        long current = allocations.getOrDefault(subsystem, 0L);
        double scaledDouble = Math.floor(current * factor);
        if (!Double.isFinite(scaledDouble) || scaledDouble > Long.MAX_VALUE || scaledDouble < 0.0d) {
            throw new IllegalArgumentException("Scaled watt value out of valid range: " + scaledDouble);
        }
        long targetWatts = (long) scaledDouble;
        allocateSubsystemWatts(subsystem, targetWatts);
        return targetWatts;
    }

    /**
     * Simulates reactor core damage or cooling-rod depletion reducing max reactor output.
     * Automatically sheds load deterministically so totalAllocatedWatts <= maxReactorOutputWattsalways holds.
     */
    public synchronized void applyReactorBrownoutCap(long newMaxReactorOutputWatts) {
        if (newMaxReactorOutputWatts < 0L || newMaxReactorOutputWatts > ABSOLUTE_MAX_REACTOR_CAPACITY) {
            throw new IllegalArgumentException("Invalid brownout cap: " + newMaxReactorOutputWatts);
        }
        this.maxReactorOutputWatts = newMaxReactorOutputWatts;
        long currentTotal = getTotalAllocatedWatts();
        if (currentTotal <= newMaxReactorOutputWatts) {
            return;
        }

        // Deterministic priority load-shedding: shed RADAR_ECM -> ARTILLERY_TURRETS -> THERMAL_SHIELDS -> PROPULSION_DRIVE -> COOLING_RODS_PUMP
        Subsystem[] shedOrder = new Subsystem[] {
                Subsystem.RADAR_ECM,
                Subsystem.ARTILLERY_TURRETS,
                Subsystem.THERMAL_SHIELDS,
                Subsystem.PROPULSION_DRIVE,
                Subsystem.COOLING_RODS_PUMP
        };
        long excess = currentTotal - newMaxReactorOutputWatts;
        for (Subsystem s : shedOrder) {
            if (excess <= 0L) {
                break;
            }
            long allocated = allocations.get(s);
            long reduction = Math.min(allocated, excess);
            allocations.put(s, allocated - reduction);
            excess -= reduction;
        }
    }

    public synchronized boolean consumeCoolingRod() {
        if (coolingRodsInstalled <= 0) {
            return false;
        }
        coolingRodsInstalled--;
        return true;
    }

    public synchronized long getTotalAllocatedWatts() {
        long sum = 0L;
        for (long w : allocations.values()) {
            sum = Math.addExact(sum, w);
        }
        return sum;
    }

    public synchronized long getAvailableReserveWatts() {
        return maxReactorOutputWatts - getTotalAllocatedWatts();
    }

    public synchronized long getMaxReactorOutputWatts() {
        return maxReactorOutputWatts;
    }

    public synchronized long getSubsystemWatts(Subsystem subsystem) {
        return allocations.getOrDefault(subsystem, 0L);
    }

    public synchronized Map<Subsystem, Long> getSnapshotAllocations() {
        return Collections.unmodifiableMap(new EnumMap<>(allocations));
    }

    public synchronized boolean verifyConservationInvariant() {
        long sum = 0L;
        for (Map.Entry<Subsystem, Long> entry : allocations.entrySet()) {
            long w = entry.getValue();
            if (w < 0L || w > entry.getKey().getSubsystemMaxWatts()) {
                return false;
            }
            sum = Math.addExact(sum, w);
        }
        return sum <= maxReactorOutputWatts && maxReactorOutputWatts >= 0L;
    }
}
