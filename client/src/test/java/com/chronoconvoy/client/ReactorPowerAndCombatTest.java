package com.chronoconvoy.client;

import com.badlogic.gdx.math.Vector2;
import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.combat.TurretCombatSystem;
import com.chronoconvoy.client.combat.TurretCombatSystem.RaiderArchetype;
import com.chronoconvoy.client.combat.TurretCombatSystem.RaiderTarget;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder.ReplayAction;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [CLI-02] Unit and integration tests for ReactorPowerController, TurretCombatSystem,
 * Scene2D EngineerControlDeckHud, and DeterministicReplayRecorder.
 */
class ReactorPowerAndCombatTest {

    private ConvoyConfig config;
    private ReactorPowerController reactor;
    private TurretCombatSystem combat;

    @BeforeEach
    void setUp() {
        config = ConvoyConfig.loadDefault();
        reactor = new ReactorPowerController(config);
        combat = new TurretCombatSystem(config, reactor);
    }

    @Test
    @DisplayName("DES-01 / CLI-01 / CLI-02: ConvoyConfig loads parametric convoy_config.json schema")
    void testConvoyConfigLoadsParametricSchema() {
        assertEquals("chrono-convoy-iron-drift-config-v1", config.getSchemaId());
        assertEquals(100.0f, config.getMaxReactorWatts(), 1e-4f);
        assertEquals(30.0f, config.getDefaultShieldsWatts(), 1e-4f);
        assertEquals(35.0f, config.getDefaultArtilleryWatts(), 1e-4f);
        assertEquals(35.0f, config.getDefaultThrustersWatts(), 1e-4f);
        assertEquals(45.0f, config.getCoolingRodHeatVentAmount(), 1e-4f);
    }

    @Test
    @DisplayName("CLI-02: ReactorPowerController enforces 100W budget and scales thrust, fire rate, and shield strength live")
    void testReactorWattAllocationAndLiveScaling() {
        reactor.allocateWatts(20.0f, 30.0f, 50.0f);
        assertEquals(100.0f, reactor.getTotalAllocatedWatts(), 1e-4f);
        assertEquals(0.0f, reactor.getUnallocatedWatts(), 1e-4f);

        float baseThrust = reactor.getThrustMultiplier();
        float baseFireRate = reactor.getArtilleryFireRateHz();
        float baseShieldCap = reactor.getShieldMaxCapacity();

        // Re-route watts heavily into Artillery and Shields, reducing Thrusters
        reactor.allocateWatts(45.0f, 45.0f, 10.0f);
        assertTrue(reactor.getShieldMaxCapacity() > baseShieldCap, "Higher Shields watts must increase Shield Dome capacity");
        assertTrue(reactor.getArtilleryFireRateHz() > baseFireRate, "Higher Artillery watts must increase Gatling Turret fire rate");
        assertTrue(reactor.getThrustMultiplier() < baseThrust, "Lower Thrusters watts must reduce locomotive thrust multiplier");

        // Reject allocations exceeding 100 Reactor Watts or negative values
        assertThrows(IllegalArgumentException.class, () -> reactor.allocateWatts(50.0f, 40.0f, 20.0f),
            "Allocating > 100W must throw IllegalArgumentException");
        assertThrows(IllegalArgumentException.class, () -> reactor.allocateWatts(-5.0f, 50.0f, 50.0f),
            "Negative watt allocation must throw IllegalArgumentException");
    }

    @Test
    @DisplayName("CLI-02 / DES-02: Overheat lockout, passive cooldown recovery, and Reactor Cooling Rod emergency sink")
    void testOverheatCooldownAndCoolingRodSink() {
        // Push all 100W into Artillery overdrive and simulate sustained thermal load until overheat
        reactor.allocateWatts(10.0f, 75.0f, 15.0f);
        for (int i = 0; i < 600 && !reactor.isOverheated(); i++) {
            reactor.update(0.1f);
        }

        assertTrue(reactor.isOverheated(), "Sustained thermal overload must trigger reactor overheat lockout");
        assertEquals(100.0f, reactor.getCurrentHeat(), 1e-3f);
        assertEquals(0.0f, reactor.getArtilleryFireRateHz(), 1e-4f, "Artillery turret must lock out during overheat");
        assertEquals(0.25f, reactor.getThrustMultiplier(), 1e-4f, "Thrusters must enter 0.25x limp mode during overheat");

        // Consume a Reactor Cooling Rod (DES-02 sink) to vent 45 heat immediately
        int initialRods = reactor.getCoolingRodsRemaining();
        assertTrue(reactor.consumeCoolingRod(), "Cooling rod should be consumed when available");
        assertEquals(initialRods - 1, reactor.getCoolingRodsRemaining());
        assertEquals(1, reactor.getCoolingRodsConsumed());
        assertEquals(55.0f, reactor.getCurrentHeat(), 1e-3f);
        assertTrue(reactor.isOverheated(), "Still above 35.0 recovery threshold after single rod from 100 heat");

        // Consume a second Cooling Rod to drop heat to 10.0 (below 35.0 recovery threshold)
        assertTrue(reactor.consumeCoolingRod());
        assertEquals(10.0f, reactor.getCurrentHeat(), 1e-3f);
        assertFalse(reactor.isOverheated(), "Dropping below recovery threshold via Cooling Rod must clear overheat lockout");
        assertTrue(reactor.getArtilleryFireRateHz() > 0.0f, "Turret fire rate must resume immediately after cooldown recovery");
    }

    @Test
    @DisplayName("CLI-02: Gatling Turret engages and destroys both RAMMER_BUGGY and MORTAR_SKIFF archetypes with pooled objects")
    void testTurretEngagesBothRaiderArchetypesAndRecyclesPools() {
        reactor.allocateWatts(35.0f, 50.0f, 15.0f);

        RaiderTarget buggy = combat.spawnRaider("buggy-alpha", RaiderArchetype.RAMMER_BUGGY, 28.0f, 3.0f, -4.0f, 0.0f);
        RaiderTarget skiff = combat.spawnRaider("skiff-bravo", RaiderArchetype.MORTAR_SKIFF, 55.0f, -6.0f, 0.0f, 0.0f);

        assertNotNull(buggy);
        assertNotNull(skiff);
        assertEquals(2, combat.getActiveRaiders().size);

        Vector2 turretPos = new Vector2(0.0f, 0.0f);
        Vector2 convoyVel = new Vector2(15.0f, 0.0f);

        // Simulate 12 seconds (720 ticks at 1/60s) of combat
        for (int tick = 0; tick < 720; tick++) {
            reactor.update(ConvoyConfig.FIXED_TIMESTEP);
            if (reactor.getCurrentHeat() > 80.0f && reactor.getCoolingRodsRemaining() > 0) {
                reactor.consumeCoolingRod();
            }
            combat.update(ConvoyConfig.FIXED_TIMESTEP, turretPos, convoyVel);
        }

        assertTrue(combat.getTotalShotsFired() >= 10, "Turret should fire bursts until both raiders are destroyed");
        assertTrue(combat.getTotalHitsLanded() >= 8, "Turret projectiles should land hits on raiders");
        assertEquals(1, combat.getRammerBuggiesDestroyed(), "Rammer Buggy must be destroyed by turret fire");
        assertEquals(1, combat.getMortarSkiffsDestroyed(), "Mortar Skiff must be destroyed by turret fire");
        assertEquals(0, combat.getActiveRaiders().size, "Destroyed raiders must be removed from active list");
        assertTrue(combat.getFreeRaiderPoolCount() >= 2, "Destroyed RaiderTarget instances must be returned to the Pool");
    }

    @Test
    @DisplayName("CLI-02: Scene2D EngineerControlDeckHud sliders route watts and deterministic replay produces identical hashes")
    void testScene2dControlDeckAndDeterministicReplayVerification() {
        long seed = 987654321L;
        String jsonRunA;
        String jsonRunB;
        String signedVerifyPayloadA;
        String signedVerifyPayloadB;
        long hashRunA;
        long hashRunB;
        List<ReplayAction> recordedSchedule;

        try (ChronoConvoySimulationSession sessionA = new ChronoConvoySimulationSession(config, seed, "verify-run-01")) {
            sessionA.getCombatSystem().spawnRaider("raider-1", RaiderArchetype.RAMMER_BUGGY, 40.0f, 4.0f, -6.0f, 0.0f);
            sessionA.getCombatSystem().spawnRaider("raider-2", RaiderArchetype.MORTAR_SKIFF, 70.0f, -8.0f, 0.0f, 0.0f);

            for (int tick = 0; tick < 240; tick++) {
                if (tick == 30) {
                    // Use Scene2D Slider actors directly to route watts
                    sessionA.getControlDeckHud().getShieldsWattSlider().setValue(25.0f);
                    sessionA.getControlDeckHud().getArtilleryWattSlider().setValue(45.0f);
                    sessionA.getControlDeckHud().getThrustersWattSlider().setValue(30.0f);
                } else if (tick == 90) {
                    sessionA.getControlDeckHud().triggerForkSwitch(ForkDirection.RIGHT_BRANCH);
                } else if (tick == 150) {
                    sessionA.getControlDeckHud().setWattSliders(40.0f, 20.0f, 40.0f);
                }
                sessionA.stepFixed();
            }

            recordedSchedule = sessionA.getReplayRecorder().getActions();
            hashRunA = sessionA.getReplayRecorder().getRollingStateHash();
            jsonRunA = sessionA.getReplayRecorder().toVerifyJsonPayload(
                sessionA.getPhysicsSystem().getCompletedTicks(),
                sessionA.getPhysicsSystem().getDistanceAlongTrackMeters(),
                sessionA.getReactorController().getCoolingRodsConsumed()
            );
            signedVerifyPayloadA = sessionA.getReplayRecorder().toSignedBackendVerifyRequestJson(
                "player-1", "convoy-alpha", "hydro_haven", "iron_refinery",
                DeterministicReplayRecorder.DEFAULT_SIGNING_SECRET
            );
        }

        // Replay the exact same seed and recorded action schedule in a fresh sessionB
        try (ChronoConvoySimulationSession sessionB = new ChronoConvoySimulationSession(config, seed, "verify-run-01")) {
            sessionB.getCombatSystem().spawnRaider("raider-1", RaiderArchetype.RAMMER_BUGGY, 40.0f, 4.0f, -6.0f, 0.0f);
            sessionB.getCombatSystem().spawnRaider("raider-2", RaiderArchetype.MORTAR_SKIFF, 70.0f, -8.0f, 0.0f, 0.0f);

            sessionB.executeReplaySchedule(recordedSchedule, 240);

            hashRunB = sessionB.getReplayRecorder().getRollingStateHash();
            jsonRunB = sessionB.getReplayRecorder().toVerifyJsonPayload(
                sessionB.getPhysicsSystem().getCompletedTicks(),
                sessionB.getPhysicsSystem().getDistanceAlongTrackMeters(),
                sessionB.getReactorController().getCoolingRodsConsumed()
            );
            signedVerifyPayloadB = sessionB.getReplayRecorder().toSignedBackendVerifyRequestJson(
                "player-1", "convoy-alpha", "hydro_haven", "iron_refinery",
                DeterministicReplayRecorder.DEFAULT_SIGNING_SECRET
            );
        }

        assertFalse(recordedSchedule.isEmpty(), "Replay recorder must capture player HUD actions");
        assertEquals(hashRunA, hashRunB, "Deterministic replay with identical seed and action log must produce identical FNV-1a hash");
        assertEquals(jsonRunA, jsonRunB, "Deterministic replay must produce identical /api/v1/run/verify JSON payload");
        assertEquals(signedVerifyPayloadA, signedVerifyPayloadB, "Signed backend VerifyRunRequest JSON must be identical");
        assertTrue(signedVerifyPayloadA.contains("\"signature_hmac\":\""), "Signed backend payload must include HMAC-SHA256 signature");
        assertTrue(jsonRunA.contains("\"state_hash_sha256\":"), "Verify payload must include SHA-256 state digest");
    }
}
