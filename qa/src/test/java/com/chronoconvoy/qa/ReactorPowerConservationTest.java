package com.chronoconvoy.qa;

import com.chronoconvoy.qa.ReactorPowerController.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QA-01B: Reactor Watt Power-Routing Conservation & Boundary Validation")
class ReactorPowerConservationTest {

    @Test
    @DisplayName("1. Nominal Subsystem Routing conserves total watts <= maxReactorOutputWatts (1200W)")
    void testNominalPowerAllocationConservation() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 450L);
        reactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 350L);
        reactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 250L);
        reactor.allocateSubsystemWatts(Subsystem.COOLING_RODS_PUMP, 150L);

        assertEquals(1200L, reactor.getTotalAllocatedWatts());
        assertEquals(0L, reactor.getAvailableReserveWatts());
        assertTrue(reactor.verifyConservationInvariant());
    }

    @Test
    @DisplayName("2. Over-Budget Watt Allocation (> maxReactorOutputWatts) is rejected without corrupting prior state")
    void testRejectOverBudgetAllocation() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 600L);
        reactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 500L);

        // 600 + 500 = 1100W used; attempting to allocate 101W to shields must fail
        assertThrows(IllegalStateException.class,
                () -> reactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 101L));

        assertEquals(1100L, reactor.getTotalAllocatedWatts(), "Failed allocation must leave prior state intact");
        assertEquals(0L, reactor.getSubsystemWatts(Subsystem.THERMAL_SHIELDS));
        assertTrue(reactor.verifyConservationInvariant());
    }

    @Test
    @DisplayName("3. Negative Watt Allocations (-1W, Long.MIN_VALUE) are strictly rejected")
    void testRejectNegativeWattAllocations() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        assertThrows(IllegalArgumentException.class,
                () -> reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> reactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, Long.MIN_VALUE));
        assertEquals(0L, reactor.getTotalAllocatedWatts());
        assertTrue(reactor.verifyConservationInvariant());
    }

    @Test
    @DisplayName("4. Integer/Long Overflow & Subsystem Cap Boundary Exploits are rejected")
    void testRejectIntegerOverflowAndSubsystemCapExploits() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 500L);

        assertThrows(IllegalArgumentException.class,
                () -> reactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> reactor.allocateSubsystemWatts(Subsystem.RADAR_ECM, Subsystem.RADAR_ECM.getSubsystemMaxWatts() + 1L));

        Map<Subsystem, Long> overflowProfile = new EnumMap<>(Subsystem.class);
        overflowProfile.put(Subsystem.PROPULSION_DRIVE, Long.MAX_VALUE);
        overflowProfile.put(Subsystem.ARTILLERY_TURRETS, 500L);
        assertThrows(IllegalArgumentException.class,
                () -> reactor.applyFullRoutingProfile(overflowProfile));

        assertEquals(500L, reactor.getTotalAllocatedWatts());
        assertTrue(reactor.verifyConservationInvariant());
    }

    @Test
    @DisplayName("5. Floating-Point Scalar Modifiers reject NaN, +/-Infinity, and negative factors")
    void testRejectNonFiniteAndNegativeScalingFactors() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 300L);

        assertThrows(IllegalArgumentException.class,
                () -> reactor.scaleSubsystemWatts(Subsystem.PROPULSION_DRIVE, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> reactor.scaleSubsystemWatts(Subsystem.PROPULSION_DRIVE, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> reactor.scaleSubsystemWatts(Subsystem.PROPULSION_DRIVE, Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> reactor.scaleSubsystemWatts(Subsystem.PROPULSION_DRIVE, -0.5d));

        assertEquals(300L, reactor.getSubsystemWatts(Subsystem.PROPULSION_DRIVE));
        assertTrue(reactor.verifyConservationInvariant());
    }

    @Test
    @DisplayName("6. Atomic Full Routing Profile rolls back completely when sum exceeds reactor capacity")
    void testAtomicRoutingProfileRollback() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 400L);
        reactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 200L);

        Map<Subsystem, Long> badProfile = new EnumMap<>(Subsystem.class);
        badProfile.put(Subsystem.PROPULSION_DRIVE, 600L);
        badProfile.put(Subsystem.ARTILLERY_TURRETS, 500L);
        badProfile.put(Subsystem.THERMAL_SHIELDS, 400L); // sum = 1500W > 1200W

        assertThrows(IllegalStateException.class, () -> reactor.applyFullRoutingProfile(badProfile));
        assertEquals(400L, reactor.getSubsystemWatts(Subsystem.PROPULSION_DRIVE));
        assertEquals(200L, reactor.getSubsystemWatts(Subsystem.THERMAL_SHIELDS));
        assertEquals(600L, reactor.getTotalAllocatedWatts());
    }

    @Test
    @DisplayName("7. Reactor Damage Brownout Cap deterministically sheds load so sum <= newMaxOutputWatts")
    void testEmergencyBrownoutLoadShedding() {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 450L);
        reactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 350L);
        reactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 200L);
        reactor.allocateSubsystemWatts(Subsystem.RADAR_ECM, 200L);
        assertEquals(1200L, reactor.getTotalAllocatedWatts());

        // Reactor hit drops output cap from 1200W to 750W
        reactor.applyReactorBrownoutCap(750L);
        assertEquals(750L, reactor.getMaxReactorOutputWatts());
        assertEquals(750L, reactor.getTotalAllocatedWatts());
        assertTrue(reactor.verifyConservationInvariant());
        // Priority order sheds RADAR_ECM (200W -> 0W) and ARTILLERY_TURRETS (350W -> 100W) first
        assertEquals(0L, reactor.getSubsystemWatts(Subsystem.RADAR_ECM));
        assertEquals(100L, reactor.getSubsystemWatts(Subsystem.ARTILLERY_TURRETS));
        assertEquals(450L, reactor.getSubsystemWatts(Subsystem.PROPULSION_DRIVE));
    }

    @Test
    @DisplayName("8. Concurrent 32-Thread Power Routing Race Stress never violates sum <= maxReactorOutputWatts")
    void testConcurrentMultiThreadedAllocationRaceSafety() throws InterruptedException {
        ReactorPowerController reactor = new ReactorPowerController(1200L);
        int threads = 32;
        int iterationsPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicBoolean invariantViolated = new AtomicBoolean(false);

        Subsystem[] subsystems = Subsystem.values();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Subsystem s = subsystems[(threadId + i) % subsystems.length];
                        long requested = ((i * 37L + threadId * 19L) % (s.getSubsystemMaxWatts() + 80L)) - 20L;
                        try {
                            reactor.allocateSubsystemWatts(s, requested);
                        } catch (IllegalArgumentException | IllegalStateException expected) {
                            // Expected when out of bounds or over total budget
                        }
                        if (!reactor.verifyConservationInvariant()) {
                            invariantViolated.set(true);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Concurrent reactor stress test timed out");
        pool.shutdownNow();

        assertFalse(invariantViolated.get(), "Reactor conservation invariant violated under concurrent load");
        assertTrue(reactor.verifyConservationInvariant());
        assertTrue(reactor.getTotalAllocatedWatts() <= 1200L);
    }
}
