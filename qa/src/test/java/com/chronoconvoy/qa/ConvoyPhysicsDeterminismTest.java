package com.chronoconvoy.qa;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QA-01A: Headless LibGDX + Box2D Multi-Car Physics & Zero-NaN/Tunneling Verification")
class ConvoyPhysicsDeterminismTest {

    @BeforeAll
    static void initHeadlessLibGdx() {
        HeadlessGdxBootstrap.init();
        assertTrue(HeadlessGdxBootstrap.isInitialized(), "Headless LibGDX & Box2D JNI must initialize cleanly");
    }

    @Test
    @DisplayName("1. Multi-Car Convoy Spline Cruise (1 Locomotive + 2 Carriages) maintains finite coordinates and coupler integrity")
    void testMultiCarConvoySplineCruiseFiniteState() {
        HeadlessGdxBootstrap.ConvoyConfig cfg = HeadlessGdxBootstrap.loadConfig();
        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(2)) {
            harness.stepTicksWithTraction(600, 450.0f); // 10 seconds at 60 Hz
            ConvoyPhysicsHarness.TelemetrySnapshot snap = harness.snapshot();

            assertEquals(600, snap.totalTicks());
            assertEquals(3, snap.carCount(), "Expected 1 Locomotive + 2 Carriages");
            assertEquals(4, snap.couplerCount(), "Expected 2 Revolute + 2 Distance coupler joints");
            assertFalse(snap.nanOrInfiniteDetected(), "Zero NaN/Infinity allowed in body positions/velocities/joints");
            assertFalse(snap.tunnelingDetected(), "Zero barrier tunneling allowed");
            assertTrue(snap.maxRecordedSpeed() > 5.0f && snap.maxRecordedSpeed() <= 70.0f);

            List<Body> cars = harness.getConvoyCars();
            for (int i = 1; i < cars.size(); i++) {
                float dist = cars.get(i - 1).getPosition().dst(cars.get(i).getPosition());
                assertTrue(dist >= 7.0f && dist <= 12.5f,
                        "Coupler separation between car " + (i - 1) + " and " + i + " out of bounds: " + dist);
            }
        }
    }

    @Test
    @DisplayName("2. High-Velocity Raider Ramming Multi-Car Convoy produces zero NaN/Infinity and valid collision impulses")
    void testHighVelocityRaiderMultiCarCollisionZeroNaN() {
        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(2)) {
            // Spawn 4 raiders on oblique and head-on intercept vectors at 95-180 m/s
            harness.spawnRaider(-60.0f, 25.0f, -95.0f, -45.0f, 6.5f, true);
            harness.spawnRaider(-70.0f, -25.0f, -85.0f, 40.0f, 6.5f, true);
            harness.spawnRaider(-40.0f, 0.0f, -150.0f, 0.0f, 9.0f, true);
            harness.spawnRaider(-160.0f, 12.0f, 110.0f, -20.0f, 7.0f, true);

            harness.stepTicksWithTraction(480, 500.0f);
            ConvoyPhysicsHarness.TelemetrySnapshot snap = harness.snapshot();

            assertFalse(snap.nanOrInfiniteDetected(), "Zero NaN or Infinite impulses/velocities during multi-raider pileup");
            assertFalse(snap.tunnelingDetected(), "Zero barrier tunneling during high-velocity pileup");
            assertTrue(snap.collisionEvents() >= 4, "Expected multiple Box2D contact events to fire, got: " + snap.collisionEvents());
            assertTrue(snap.maxNormalImpulse() > 0.0f && Float.isFinite(snap.maxNormalImpulse()),
                    "Max normal impulse must be positive and finite");
        }
    }

    @Test
    @DisplayName("3. Supersonic Bullet/CCD Barrier Impact (450 m/s) prevents tunneling through perimeter canyon walls")
    void testSupersonicBulletBarrierZeroTunneling() {
        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(2)) {
            // Spawn supersonic projectiles aimed directly at North, South, East, West barriers with CCD enabled
            harness.spawnRaider(0.0f, 90.0f, 0.0f, 450.0f, 10.0f, true);
            harness.spawnRaider(0.0f, -90.0f, 0.0f, -450.0f, 10.0f, true);
            harness.spawnRaider(210.0f, 0.0f, 450.0f, 0.0f, 10.0f, true);
            harness.spawnRaider(-210.0f, 0.0f, -450.0f, 0.0f, 10.0f, true);

            for (int tick = 0; tick < 300; tick++) {
                harness.stepSingleTick();
            }

            ConvoyPhysicsHarness.TelemetrySnapshot snap = harness.snapshot();
            assertFalse(snap.nanOrInfiniteDetected(), "Supersonic CCD impact must not generate NaN/Infinity");
            assertFalse(snap.tunnelingDetected(), "CCD (bullet=true) bodies must never tunnel past static canyon barriers");

            for (Body raider : harness.getRaiderBodies()) {
                Vector2 pos = raider.getPosition();
                assertTrue(Math.abs(pos.x) <= ConvoyPhysicsHarness.ARENA_HALF_WIDTH,
                        "Raider X tunneled outside arena: " + pos.x);
                assertTrue(Math.abs(pos.y) <= ConvoyPhysicsHarness.ARENA_HALF_HEIGHT,
                        "Raider Y tunneled outside arena: " + pos.y);
            }
        }
    }

    @Test
    @DisplayName("4. Bit-Exact Deterministic Replay: Twin independent Box2D worlds yield identical SHA-256 state hashes after 600 ticks")
    void testBitExactDeterminismAcrossTwinSimulations() {
        String hashRunA;
        String hashRunB;

        try (ConvoyPhysicsHarness worldA = new ConvoyPhysicsHarness(2)) {
            worldA.spawnRaider(-55.0f, 14.0f, -120.0f, -28.0f, 7.5f, true);
            worldA.spawnRaider(-90.0f, -18.0f, -75.0f, 32.0f, 5.5f, true);
            worldA.stepTicksWithTraction(600, 420.0f);
            hashRunA = worldA.snapshot().deterministicStateHash();
        }

        try (ConvoyPhysicsHarness worldB = new ConvoyPhysicsHarness(2)) {
            worldB.spawnRaider(-55.0f, 14.0f, -120.0f, -28.0f, 7.5f, true);
            worldB.spawnRaider(-90.0f, -18.0f, -75.0f, 32.0f, 5.5f, true);
            worldB.stepTicksWithTraction(600, 420.0f);
            hashRunB = worldB.snapshot().deterministicStateHash();
        }

        assertNotNull(hashRunA);
        assertEquals(hashRunA, hashRunB, "Deterministic fixed-timestep Box2D simulation must produce identical SHA-256 state hash");
    }

    @Test
    @DisplayName("5. Emergency Braking & Articulated Jackknife Compression maintains joint stability without solver explosion")
    void testEmergencyBrakingJackknifeStability() {
        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(4)) { // 1 Locomotive + 4 Carriages (5-car heavy train)
            harness.stepTicksWithTraction(240, 580.0f);
            // Apply sudden reverse braking impulse to Locomotive while rear cars push forward
            Body loco = harness.getConvoyCars().get(0);
            loco.setLinearVelocity(-25.0f, 8.0f);

            harness.stepTicksWithTraction(360, 0.0f);
            ConvoyPhysicsHarness.TelemetrySnapshot snap = harness.snapshot();

            assertFalse(snap.nanOrInfiniteDetected(), "Articulated 5-car jackknife compression must not cause joint solver NaN");
            assertFalse(snap.tunnelingDetected(), "Articulated 5-car jackknife must remain inside arena bounds");
        }
    }

    @Test
    @DisplayName("6. Long-Horizon 3,600-Tick (60-Second Simulated) Combat Endurance Run")
    void testLongHorizon3600TickEnduranceRun() {
        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(2)) {
            for (int wave = 0; wave < 6; wave++) {
                float offset = wave * 12.0f;
                harness.spawnRaider(-20.0f + offset, (wave % 2 == 0) ? 20.0f : -20.0f, -90.0f, (wave % 2 == 0) ? -35.0f : 35.0f, 6.0f, true);
                harness.stepTicksWithTraction(600, 380.0f);
            }
            ConvoyPhysicsHarness.TelemetrySnapshot snap = harness.snapshot();
            assertEquals(3600, snap.totalTicks());
            assertFalse(snap.nanOrInfiniteDetected());
            assertFalse(snap.tunnelingDetected());
        }
    }

    @Test
    @DisplayName("7. Boundary Input Guard: Rejects NaN/Infinite raider spawn parameters and negative traction watts")
    void testRejectsNonFinitePhysicsInputs() {
        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(2)) {
            assertThrows(IllegalArgumentException.class,
                    () -> harness.spawnRaider(Float.NaN, 0f, 10f, 10f, 5f, true));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.spawnRaider(0f, 0f, Float.POSITIVE_INFINITY, 10f, 5f, true));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.spawnRaider(0f, 0f, 10f, 10f, -1.0f, true));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.applyLocomotiveSplineTraction(Float.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.applyLocomotiveSplineTraction(-50.0f));
        }
    }

    @Test
    @DisplayName("8. Native Memory Lifecycle: Disposable World cleanup prevents native leaks and blocks post-dispose stepping")
    void testDisposableWorldLifecycle() {
        ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(2);
        harness.stepTicksWithTraction(60, 300.0f);
        assertFalse(harness.isDisposed());
        harness.dispose();
        assertTrue(harness.isDisposed());
        assertThrows(IllegalStateException.class, harness::stepSingleTick);
    }
}
