package com.chronoconvoy.client;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJoint;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem.CarriageUnit;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem.RammingCollisionEvent;
import com.chronoconvoy.client.track.SplineTrackSystem;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;
import com.chronoconvoy.client.track.SplineTrackSystem.TrackSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [CLI-01] Unit and integration tests for SplineTrackSystem and ConvoyPhysicsSystem.
 */
class ConvoyPhysicsSystemTest {

    private ConvoyConfig config;
    private SplineTrackSystem trackSystem;
    private ConvoyPhysicsSystem physicsSystem;

    @BeforeEach
    void setUp() {
        config = ConvoyConfig.loadDefault();
        trackSystem = new SplineTrackSystem(config, 133742L);
        physicsSystem = new ConvoyPhysicsSystem(config, trackSystem, 3);
    }

    @AfterEach
    void tearDown() {
        if (physicsSystem != null && !physicsSystem.isDisposed()) {
            physicsSystem.dispose();
        }
    }

    @Test
    @DisplayName("CLI-01: Locomotive + 3 articulated carriages are linked via Box2D RevoluteJoints with limits")
    void testMultiCarriageRevoluteJointArticulation() {
        List<CarriageUnit> carriages = physicsSystem.getCarriages();
        List<RevoluteJoint> joints = physicsSystem.getRevoluteJoints();

        assertEquals(3, carriages.size(), "Convoy should have 3 articulated carriages attached to the locomotive");
        assertEquals(3, joints.size(), "Convoy should have 3 RevoluteJoint couplers");

        Body expectedLeadBody = physicsSystem.getLocomotiveBody();
        for (int i = 0; i < carriages.size(); i++) {
            CarriageUnit carriage = carriages.get(i);
            RevoluteJoint joint = joints.get(i);

            assertSame(joint, carriage.getCouplingJoint());
            assertSame(expectedLeadBody, joint.getBodyA(), "RevoluteJoint BodyA should be preceding car");
            assertSame(carriage.getBody(), joint.getBodyB(), "RevoluteJoint BodyB should be current carriage");
            assertTrue(joint.isLimitEnabled(), "RevoluteJoint angular limit must be enabled");
            assertEquals(-config.getRevoluteJointLimitRadians(), joint.getLowerLimit(), 1e-4f);
            assertEquals(config.getRevoluteJointLimitRadians(), joint.getUpperLimit(), 1e-4f);

            expectedLeadBody = carriage.getBody();
        }

        // Step 5 seconds (300 ticks at 1/60s) along undulating spline track and verify carriages stay coupled in order
        physicsSystem.setThrottleInput(1.0f);
        for (int tick = 0; tick < 300; tick++) {
            physicsSystem.stepFixed();
        }

        assertTrue(physicsSystem.getDistanceAlongTrackMeters() > 50.0f, "Convoy should advance along the spline track");
        float prevX = physicsSystem.getLocomotiveBody().getPosition().x;
        for (CarriageUnit carriage : carriages) {
            float carX = carriage.getBody().getPosition().x;
            assertTrue(carX < prevX, "Trailing carriage must remain behind preceding car along the track");
            prevX = carX;
        }
    }

    @Test
    @DisplayName("CLI-01: Continuous Catmull-Rom spline scrolls endlessly at 60 FPS and recycles pooled TrackSamples")
    void testContinuousSplineTrackScrollingAndPooling() {
        TrackSample sample = trackSystem.obtainSample();
        for (int step = 0; step < 600; step++) {
            float distance = step * 15.0f; // Scroll across 9,000 meters of wasteland track
            trackSystem.sampleAtDistance(distance, sample);

            assertEquals(1.0f, sample.tangent.len(), 1e-3f, "Spline tangent must be unit length");
            assertEquals(1.0f, sample.normal.len(), 1e-3f, "Spline normal must be unit length");
            assertEquals(0.0f, sample.tangent.dot(sample.normal), 1e-3f, "Tangent and normal must be orthogonal");
            assertTrue(Float.isFinite(sample.curvature), "Spline curvature must be finite");
        }
        trackSystem.freeSample(sample);

        assertTrue(trackSystem.getBaseSegmentIndex() > 50, "Sliding spline window must advance as distance grows");
        assertTrue(trackSystem.getFreeSamplePoolCount() >= 1, "Freed TrackSample must return to the pool");
    }

    @Test
    @DisplayName("CLI-01: Track fork switching shifts trajectory laterally onto LEFT_BRANCH and RIGHT_BRANCH")
    void testTrackForkSwitching() {
        SplineTrackSystem mainTrack = new SplineTrackSystem(config, 999L);
        SplineTrackSystem leftTrack = new SplineTrackSystem(config, 999L);
        SplineTrackSystem rightTrack = new SplineTrackSystem(config, 999L);

        leftTrack.switchFork(ForkDirection.LEFT_BRANCH);
        rightTrack.switchFork(ForkDirection.RIGHT_BRANCH);

        TrackSample mainSample = mainTrack.obtainSample();
        TrackSample leftSample = leftTrack.obtainSample();
        TrackSample rightSample = rightTrack.obtainSample();

        try {
            mainTrack.sampleAtDistance(400.0f, mainSample);
            leftTrack.sampleAtDistance(400.0f, leftSample);
            rightTrack.sampleAtDistance(400.0f, rightSample);

            assertEquals(ForkDirection.LEFT_BRANCH, leftTrack.getActiveFork());
            assertEquals(ForkDirection.RIGHT_BRANCH, rightTrack.getActiveFork());
            assertEquals(1, leftTrack.getForkSwitchCount());
            assertTrue(leftSample.position.y > mainSample.position.y + 5.0f,
                "LEFT_BRANCH should shift track laterally in +Y relative to MAIN_LINE");
            assertTrue(rightSample.position.y < mainSample.position.y - 5.0f,
                "RIGHT_BRANCH should shift track laterally in -Y relative to MAIN_LINE");
        } finally {
            mainTrack.freeSample(mainSample);
            leftTrack.freeSample(leftSample);
            rightTrack.freeSample(rightSample);
        }
    }

    @Test
    @DisplayName("CLI-01 / DES-01: Dynamic cargo mass measurably changes acceleration and braking distance")
    void testDynamicCargoMassAffectsAccelerationAndBraking() {
        SplineTrackSystem lightTrack = new SplineTrackSystem(config, 2026L);
        SplineTrackSystem heavyTrack = new SplineTrackSystem(config, 2026L);

        try (ConvoyPhysicsSystem lightConvoy = new ConvoyPhysicsSystem(config, lightTrack, 3);
             ConvoyPhysicsSystem heavyConvoy = new ConvoyPhysicsSystem(config, heavyTrack, 3)) {

            lightConvoy.setTotalCargoMassTons(0.0f);
            heavyConvoy.setTotalCargoMassTons(120.0f); // 40 tons per carriage

            assertEquals(config.getLocomotiveBaseMassTons() + 3 * config.getCarriageBaseMassTons(),
                lightConvoy.getTotalConvoyMassTons(), 1e-3f);
            assertEquals(lightConvoy.getTotalConvoyMassTons() + 120.0f,
                heavyConvoy.getTotalConvoyMassTons(), 1e-3f);

            // 1. Acceleration comparison over 180 ticks (3.0 seconds) at full throttle
            lightConvoy.setThrottleInput(1.0f);
            heavyConvoy.setThrottleInput(1.0f);
            for (int i = 0; i < 90; i++) {
                lightConvoy.stepFixed();
                heavyConvoy.stepFixed();
            }

            assertTrue(lightConvoy.getForwardSpeedMps() > heavyConvoy.getForwardSpeedMps() * 1.35f,
                "Light convoy must accelerate to a significantly higher speed than heavy cargo convoy");
            assertTrue(lightConvoy.getDistanceAlongTrackMeters() > heavyConvoy.getDistanceAlongTrackMeters(),
                "Light convoy must cover more distance during acceleration than heavy cargo convoy");

            // 2. Braking comparison from an identical initial cruising speed of 30.0 m/s
            lightConvoy.setThrottleInput(0.0f);
            heavyConvoy.setThrottleInput(0.0f);
            lightConvoy.setBrakeInput(1.0f);
            heavyConvoy.setBrakeInput(1.0f);
            lightConvoy.setForwardSpeedMps(30.0f);
            heavyConvoy.setForwardSpeedMps(30.0f);

            float lightStartDist = lightConvoy.getDistanceAlongTrackMeters();
            float heavyStartDist = heavyConvoy.getDistanceAlongTrackMeters();

            assertTrue(heavyConvoy.computeEffectiveBrakingForceNewtons() > lightConvoy.computeEffectiveBrakingForceNewtons(),
                "DES-01 per-ton braking friction increases raw brake shoe force with cargo tonnage");
            assertTrue(lightConvoy.computeInstantaneousBrakingDecelerationMps2() > heavyConvoy.computeInstantaneousBrakingDecelerationMps2(),
                "Higher total cargo inertia reduces net braking deceleration (m/s^2)");

            int lightTicksToStop = 0;
            int heavyTicksToStop = 0;
            for (int tick = 1; tick <= 1200; tick++) {
                if (lightConvoy.getForwardSpeedMps() > 0.001f) {
                    lightConvoy.stepFixed();
                    lightTicksToStop = tick;
                }
                if (heavyConvoy.getForwardSpeedMps() > 0.001f) {
                    heavyConvoy.stepFixed();
                    heavyTicksToStop = tick;
                }
            }

            float lightStoppingDistance = lightConvoy.getDistanceAlongTrackMeters() - lightStartDist;
            float heavyStoppingDistance = heavyConvoy.getDistanceAlongTrackMeters() - heavyStartDist;

            assertTrue(heavyTicksToStop > lightTicksToStop,
                "Heavy cargo convoy must take more ticks to brake to a complete stop");
            assertTrue(heavyStoppingDistance > lightStoppingDistance * 1.25f,
                "Heavy cargo convoy must require a measurably longer braking distance to stop");
        }
    }

    @Test
    @DisplayName("CLI-01: Momentum-based ramming impulse scales with convoy mass and speed and recycles pooled events")
    void testMomentumBasedRammingImpulseAndEventPooling() {
        SplineTrackSystem lightTrack = new SplineTrackSystem(config, 777L);
        SplineTrackSystem heavyTrack = new SplineTrackSystem(config, 777L);

        try (ConvoyPhysicsSystem lightConvoy = new ConvoyPhysicsSystem(config, lightTrack, 3);
             ConvoyPhysicsSystem heavyConvoy = new ConvoyPhysicsSystem(config, heavyTrack, 3)) {

            lightConvoy.setTotalCargoMassTons(0.0f);
            heavyConvoy.setTotalCargoMassTons(150.0f);

            lightConvoy.setForwardSpeedMps(28.0f);
            heavyConvoy.setForwardSpeedMps(28.0f);

            Body lightTarget = lightConvoy.createObstacleBody("buggy-light-test", 5.0f, 0.0f, 12.0f, -10.0f, 0.0f);
            Body heavyTarget = heavyConvoy.createObstacleBody("buggy-heavy-test", 5.0f, 0.0f, 12.0f, -10.0f, 0.0f);

            RammingCollisionEvent lightImpact = lightConvoy.resolveRammingImpact("buggy-light-test", lightTarget, 12.0f);
            RammingCollisionEvent heavyImpact = heavyConvoy.resolveRammingImpact("buggy-heavy-test", heavyTarget, 12.0f);

            assertTrue(heavyImpact.impulseMagnitudeNs > lightImpact.impulseMagnitudeNs,
                "Heavier cargo-laden convoy must deliver a larger momentum-based ramming impulse");
            assertTrue(heavyImpact.convoyMomentumTransfer > lightImpact.convoyMomentumTransfer,
                "Heavier convoy must carry greater total momentum into the collision");
            assertTrue(heavyImpact.hullDamageDealt > lightImpact.hullDamageDealt,
                "Heavier convoy ramming impact must deal more hull damage");
            assertTrue(heavyImpact.convoySpeedLossMps < lightImpact.convoySpeedLossMps,
                "Heavier convoy must lose less forward speed upon ramming an identical target");

            assertEquals(1, lightConvoy.getActiveRammingEvents().size);
            lightConvoy.clearAndFreeRammingEvents();
            assertEquals(0, lightConvoy.getActiveRammingEvents().size);
            assertTrue(lightConvoy.getFreeRammingEventPoolCount() >= 1,
                "Cleared RammingCollisionEvent must be recycled back into the Pool");
        }
    }

    @Test
    @DisplayName("CLI-01: Fixed-timestep (1/60s) accumulator produces deterministic simulation regardless of frame delta chunking")
    void testFixedTimestepAccumulatorDeterminism() {
        SplineTrackSystem trackA = new SplineTrackSystem(config, 55555L);
        SplineTrackSystem trackB = new SplineTrackSystem(config, 55555L);

        try (ConvoyPhysicsSystem simA = new ConvoyPhysicsSystem(config, trackA, 3);
             ConvoyPhysicsSystem simB = new ConvoyPhysicsSystem(config, trackB, 3)) {

            simA.setTotalCargoMassTons(45.0f);
            simB.setTotalCargoMassTons(45.0f);

            // Step simA with 120 exact 1/60s calls (2.0 seconds total)
            for (int i = 0; i < 120; i++) {
                simA.update(ConvoyConfig.FIXED_TIMESTEP);
            }

            // Step simB with 40 chunked 3/60s (0.05s) calls (2.0 seconds total)
            for (int i = 0; i < 40; i++) {
                simB.update(ConvoyConfig.FIXED_TIMESTEP * 3.0f);
            }

            assertEquals(120L, simA.getCompletedTicks());
            assertEquals(120L, simB.getCompletedTicks());
            assertEquals(simA.getDistanceAlongTrackMeters(), simB.getDistanceAlongTrackMeters(), 1e-5f);
            assertEquals(simA.getForwardSpeedMps(), simB.getForwardSpeedMps(), 1e-5f);
            assertEquals(simA.getLocomotiveBody().getPosition().x, simB.getLocomotiveBody().getPosition().x, 1e-5f);
            assertEquals(simA.getLocomotiveBody().getPosition().y, simB.getLocomotiveBody().getPosition().y, 1e-5f);
        }
    }
}
