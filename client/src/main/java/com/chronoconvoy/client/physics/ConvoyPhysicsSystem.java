package com.chronoconvoy.client.physics;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.badlogic.gdx.physics.box2d.MassData;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJoint;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJointDef;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Pool;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.track.SplineTrackSystem;
import com.chronoconvoy.client.track.SplineTrackSystem.TrackSample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * [CLI-01] Deterministic fixed-timestep (1/60s) Box2D multi-car convoy physics system.
 * Models a Locomotive + N articulated carriages (default 3) linked via Box2D RevoluteJoints,
 * dynamic cargo mass affecting inertia/acceleration/braking, continuous Catmull-Rom spline
 * track guidance, and pooled momentum-based ramming collision impulse calculation.
 */
public final class ConvoyPhysicsSystem implements Disposable, AutoCloseable {

    static {
        Box2D.init();
    }

    /**
     * Poolable ramming collision event to avoid GC allocations during combat/collision steps.
     */
    public static final class RammingCollisionEvent implements Pool.Poolable {
        public long tickIndex;
        public String targetId = "";
        public float convoyMassTons;
        public float targetMassTons;
        public float closingSpeedMps;
        public float impulseMagnitudeNs;
        public float convoyMomentumTransfer;
        public float hullDamageDealt;
        public float convoySpeedLossMps;
        public final Vector2 contactPointWorld = new Vector2();
        public final Vector2 impulseNormal = new Vector2(1.0f, 0.0f);

        @Override
        public void reset() {
            tickIndex = 0L;
            targetId = "";
            convoyMassTons = 0.0f;
            targetMassTons = 0.0f;
            closingSpeedMps = 0.0f;
            impulseMagnitudeNs = 0.0f;
            convoyMomentumTransfer = 0.0f;
            hullDamageDealt = 0.0f;
            convoySpeedLossMps = 0.0f;
            contactPointWorld.setZero();
            impulseNormal.set(1.0f, 0.0f);
        }
    }

    /**
     * Represents an individual articulated carriage attached behind the locomotive.
     */
    public static final class CarriageUnit {
        private final int carriageIndex;
        private final String roleName;
        private final Body body;
        private final RevoluteJoint couplingJoint;
        private final float baseMassTons;
        private float cargoMassTons;

        CarriageUnit(int carriageIndex, String roleName, Body body, RevoluteJoint couplingJoint, float baseMassTons) {
            this.carriageIndex = carriageIndex;
            this.roleName = roleName;
            this.body = body;
            this.couplingJoint = couplingJoint;
            this.baseMassTons = baseMassTons;
            this.cargoMassTons = 0.0f;
        }

        public int getCarriageIndex() { return carriageIndex; }
        public String getRoleName() { return roleName; }
        public Body getBody() { return body; }
        public RevoluteJoint getCouplingJoint() { return couplingJoint; }
        public float getBaseMassTons() { return baseMassTons; }
        public float getCargoMassTons() { return cargoMassTons; }
        public float getTotalMassTons() { return baseMassTons + cargoMassTons; }

        void setCargoMassTons(float cargoMassTons) {
            this.cargoMassTons = Math.max(0.0f, cargoMassTons);
            MassData md = body.getMassData();
            md.mass = this.baseMassTons + this.cargoMassTons;
            md.I = md.mass * 4.5f;
            body.setMassData(md);
        }
    }

    /**
     * Metadata attached to enemy/obstacle Box2D bodies for automatic ContactListener ramming resolution.
     */
    public static final class ObstacleTag {
        public final String id;
        public final float massTons;
        public boolean rammed;

        public ObstacleTag(String id, float massTons) {
            this.id = id != null ? id : "obstacle";
            this.massTons = Math.max(1.0f, massTons);
        }
    }

    private static final String[] DEFAULT_CARRIAGE_ROLES = {
        "GATLING_ARTILLERY_CAR",
        "SHIELD_DOME_CAR",
        "ARMORED_CARGO_CAR"
    };

    private final ConvoyConfig config;
    private final SplineTrackSystem trackSystem;
    private final World world;
    private final Body locomotiveBody;
    private final List<CarriageUnit> carriages;
    private final List<RevoluteJoint> revoluteJoints;

    // Pre-allocated scratch vectors for zero-allocation fixed-timestep updates
    private final Vector2 scratchForce = new Vector2();
    private final Vector2 scratchVel = new Vector2();
    private final Vector2 scratchTargetPos = new Vector2();
    private final Vector2 scratchImpulse = new Vector2();
    private final Vector2 scratchRelVel = new Vector2();

    private final Pool<RammingCollisionEvent> rammingEventPool = new Pool<RammingCollisionEvent>(16, 64) {
        @Override
        protected RammingCollisionEvent newObject() {
            return new RammingCollisionEvent();
        }
    };
    private final Array<RammingCollisionEvent> activeRammingEvents = new Array<>(false, 16);

    private float accumulatorSeconds = 0.0f;
    private long completedTicks = 0L;
    private float throttleInput = 1.0f;     // [0.0 .. 1.0]
    private float brakeInput = 0.0f;        // [0.0 .. 1.0]
    private float thrusterPowerMultiplier = 1.0f;
    private float distanceAlongTrackMeters = 0.0f;
    private float forwardSpeedMps = 0.0f;
    private boolean disposed = false;

    public ConvoyPhysicsSystem(ConvoyConfig config, SplineTrackSystem trackSystem) {
        this(config, trackSystem, config.getDefaultCarriageCount());
    }

    public ConvoyPhysicsSystem(ConvoyConfig config, SplineTrackSystem trackSystem, int carriageCount) {
        this.config = Objects.requireNonNull(config, "config");
        this.trackSystem = Objects.requireNonNull(trackSystem, "trackSystem");
        if (carriageCount < 1) {
            throw new IllegalArgumentException("carriageCount must be >= 1");
        }

        this.world = new World(new Vector2(0.0f, 0.0f), false);
        this.locomotiveBody = createVehicleBody(0.0f, 0.0f, 4.0f, 1.6f, config.getLocomotiveBaseMassTons(), "LOCOMOTIVE");

        List<CarriageUnit> carriageList = new ArrayList<>(carriageCount);
        List<RevoluteJoint> jointList = new ArrayList<>(carriageCount);

        Body previousBody = this.locomotiveBody;
        float spacing = config.getCarriageSpacingMeters();

        for (int i = 0; i < carriageCount; i++) {
            float centerX = -((i + 1) * spacing);
            String role = DEFAULT_CARRIAGE_ROLES[i % DEFAULT_CARRIAGE_ROLES.length];
            Body carriageBody = createVehicleBody(centerX, 0.0f, 3.6f, 1.5f, config.getCarriageBaseMassTons(), role);

            RevoluteJointDef jointDef = new RevoluteJointDef();
            Vector2 couplerWorldAnchor = new Vector2(centerX + (spacing * 0.5f), 0.0f);
            jointDef.initialize(previousBody, carriageBody, couplerWorldAnchor);
            jointDef.collideConnected = false;
            jointDef.enableLimit = true;
            jointDef.lowerAngle = -config.getRevoluteJointLimitRadians();
            jointDef.upperAngle = config.getRevoluteJointLimitRadians();

            RevoluteJoint joint = (RevoluteJoint) world.createJoint(jointDef);
            CarriageUnit unit = new CarriageUnit(i, role, carriageBody, joint, config.getCarriageBaseMassTons());
            carriageList.add(unit);
            jointList.add(joint);

            previousBody = carriageBody;
        }

        this.carriages = Collections.unmodifiableList(carriageList);
        this.revoluteJoints = Collections.unmodifiableList(jointList);

        installContactListener();
    }

    private Body createVehicleBody(float xMeters, float yMeters, float halfLengthMeters, float halfWidthMeters, float massTons, Object userData) {
        BodyDef bodyDef = new BodyDef();
        bodyDef.type = BodyDef.BodyType.DynamicBody;
        bodyDef.position.set(xMeters, yMeters);
        bodyDef.linearDamping = 0.05f;
        bodyDef.angularDamping = 2.5f;
        bodyDef.bullet = true;

        Body body = world.createBody(bodyDef);
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(halfLengthMeters, halfWidthMeters);
            FixtureDef fixtureDef = new FixtureDef();
            fixtureDef.shape = shape;
            fixtureDef.density = 1.0f;
            fixtureDef.friction = 0.2f;
            fixtureDef.restitution = 0.1f;
            body.createFixture(fixtureDef);
        } finally {
            shape.dispose();
        }

        MassData massData = body.getMassData();
        massData.mass = massTons;
        massData.center.setZero();
        massData.I = massTons * 5.0f;
        body.setMassData(massData);
        body.setUserData(userData);
        return body;
    }

    private void installContactListener() {
        world.setContactListener(new ContactListener() {
            @Override
            public void beginContact(Contact contact) {
                if (contact.getFixtureA() == null || contact.getFixtureB() == null) {
                    return;
                }
                Body bodyA = contact.getFixtureA().getBody();
                Body bodyB = contact.getFixtureB().getBody();
                if (bodyA == locomotiveBody && bodyB.getUserData() instanceof ObstacleTag tag && !tag.rammed) {
                    tag.rammed = true;
                    resolveRammingImpact(tag.id, bodyB, tag.massTons);
                } else if (bodyB == locomotiveBody && bodyA.getUserData() instanceof ObstacleTag tag && !tag.rammed) {
                    tag.rammed = true;
                    resolveRammingImpact(tag.id, bodyA, tag.massTons);
                }
            }

            @Override
            public void endContact(Contact contact) {
            }

            @Override
            public void preSolve(Contact contact, Manifold oldManifold) {
            }

            @Override
            public void postSolve(Contact contact, ContactImpulse impulse) {
            }
        });
    }

    /**
     * Advances the fixed-timestep accumulator by deltaSeconds, stepping the Box2D simulation
     * in deterministic 1/60s increments.
     *
     * @return number of fixed 1/60s steps executed during this call
     */
    public int update(float deltaSeconds) {
        if (disposed) {
            throw new IllegalStateException("ConvoyPhysicsSystem has been disposed");
        }
        float clampedDelta = Math.min(Math.max(0.0f, deltaSeconds), 0.25f);
        accumulatorSeconds += clampedDelta;
        int stepsExecuted = 0;
        while (accumulatorSeconds + 1e-7f >= ConvoyConfig.FIXED_TIMESTEP) {
            stepFixed();
            accumulatorSeconds -= ConvoyConfig.FIXED_TIMESTEP;
            if (accumulatorSeconds < 0.0f) {
                accumulatorSeconds = 0.0f;
            }
            stepsExecuted++;
        }
        return stepsExecuted;
    }

    /**
     * Executes exactly one deterministic 1/60s physics step.
     * Zero heap allocations occur along this hot path.
     */
    public void stepFixed() {
        if (disposed) {
            throw new IllegalStateException("ConvoyPhysicsSystem has been disposed");
        }
        float dt = ConvoyConfig.FIXED_TIMESTEP;
        float totalMassTons = getTotalConvoyMassTons();

        TrackSample locoSample = trackSystem.obtainSample();
        try {
            trackSystem.sampleAtDistance(distanceAlongTrackMeters, locoSample);

            // Compute net longitudinal traction & braking forces (DES-01 mass-dependent dynamics)
            float activeThrustForce = throttleInput * config.getBaseThrustForceNewtons() * thrusterPowerMultiplier;
            float activeBrakingForce = brakeInput * computeEffectiveBrakingForceNewtons();
            float rollingResistance = forwardSpeedMps * config.getRollingResistanceCoeff() * totalMassTons;

            float netLongitudinalForce = activeThrustForce - activeBrakingForce - rollingResistance;
            float longitudinalAccelMps2 = netLongitudinalForce / totalMassTons;

            forwardSpeedMps += longitudinalAccelMps2 * dt;
            if (brakeInput > 0.0f && forwardSpeedMps < 0.0f) {
                forwardSpeedMps = 0.0f;
            }
            forwardSpeedMps = MathUtils.clamp(forwardSpeedMps, 0.0f, config.getMaxCruisingSpeedMps() * Math.max(1.0f, thrusterPowerMultiplier));
            distanceAlongTrackMeters += forwardSpeedMps * dt;

            // Sample updated locomotive position on continuous spline
            trackSystem.sampleAtDistance(distanceAlongTrackMeters, locoSample);

            // Guide locomotive along spline tangent with lateral spring-damper constraint
            guideBodyAlongSpline(locomotiveBody, locoSample, forwardSpeedMps, dt);

            // Guide each articulated carriage along its trailing arc-length offset while preserving RevoluteJoint articulation
            float spacing = config.getCarriageSpacingMeters();
            for (int i = 0; i < carriages.size(); i++) {
                CarriageUnit carriage = carriages.get(i);
                float carriageDist = distanceAlongTrackMeters - ((i + 1) * spacing);
                TrackSample carSample = trackSystem.obtainSample();
                try {
                    trackSystem.sampleAtDistance(carriageDist, carSample);
                    guideBodyAlongSpline(carriage.getBody(), carSample, forwardSpeedMps, dt);
                } finally {
                    trackSystem.freeSample(carSample);
                }
            }

            // Step Box2D world deterministically
            world.step(dt, ConvoyConfig.VELOCITY_ITERATIONS, ConvoyConfig.POSITION_ITERATIONS);
            completedTicks++;
        } finally {
            trackSystem.freeSample(locoSample);
        }
    }

    private void guideBodyAlongSpline(Body body, TrackSample sample, float targetSpeedMps, float dt) {
        Vector2 currentPos = body.getPosition();
        float dx = sample.position.x - currentPos.x;
        float dy = sample.position.y - currentPos.y;

        // Lateral error along spline normal
        float lateralError = dx * sample.normal.x + dy * sample.normal.y;
        float clampedLateralError = MathUtils.clamp(lateralError, -config.getMaxLateralDriftMeters(), config.getMaxLateralDriftMeters());

        // Desired velocity combines forward tangent velocity and lateral restoring correction
        float restoreRate = config.getLateralGuidanceStiffness();
        float vx = sample.tangent.x * targetSpeedMps + (dx * restoreRate) + (clampedLateralError * sample.normal.x * restoreRate * 0.5f);
        float vy = sample.tangent.y * targetSpeedMps + (dy * restoreRate) + (clampedLateralError * sample.normal.y * restoreRate * 0.5f);

        scratchVel.set(vx, vy);
        body.setLinearVelocity(scratchVel);

        float targetAngle = MathUtils.atan2(sample.tangent.y, sample.tangent.x);
        float angleDiff = MathUtils.atan2(MathUtils.sin(targetAngle - body.getAngle()), MathUtils.cos(targetAngle - body.getAngle()));
        body.setAngularVelocity(angleDiff / dt * 0.35f);
    }

    /**
     * Spawns a Box2D dynamic obstacle/raider body on or near the track for collision and ramming simulation.
     */
    public Body createObstacleBody(String obstacleId, float xMeters, float yMeters, float massTons, float initialVx, float initialVy) {
        Body body = createVehicleBody(xMeters, yMeters, 2.0f, 1.2f, massTons, new ObstacleTag(obstacleId, massTons));
        body.setLinearVelocity(initialVx, initialVy);
        return body;
    }

    /**
     * Computes and applies a momentum-based ramming collision impulse between the convoy locomotive
     * and an obstacle/raider Box2D body, recording a pooled RammingCollisionEvent with zero heap allocations.
     */
    public RammingCollisionEvent resolveRammingImpact(String targetId, Body obstacleBody, float obstacleMassTons) {
        Objects.requireNonNull(obstacleBody, "obstacleBody");
        float effectiveObstacleMass = Math.max(1.0f, obstacleMassTons);
        float totalConvoyMass = getTotalConvoyMassTons();

        // Compute impact normal from locomotive to obstacle (fallback to locomotive forward tangent)
        scratchImpulse.set(obstacleBody.getPosition()).sub(locomotiveBody.getPosition());
        if (scratchImpulse.len2() < 1e-5f) {
            float angle = locomotiveBody.getAngle();
            scratchImpulse.set(MathUtils.cos(angle), MathUtils.sin(angle));
        } else {
            scratchImpulse.nor();
        }

        // Relative velocity along impact normal
        scratchRelVel.set(locomotiveBody.getLinearVelocity()).sub(obstacleBody.getLinearVelocity());
        float closingSpeed = Math.max(0.0f, scratchRelVel.dot(scratchImpulse));
        if (closingSpeed < 0.01f && forwardSpeedMps > 0.0f) {
            closingSpeed = forwardSpeedMps;
        }

        // Reduced mass (effective collision mass): mu = (M_convoy * M_target) / (M_convoy + M_target)
        float reducedMass = (totalConvoyMass * effectiveObstacleMass) / (totalConvoyMass + effectiveObstacleMass);
        float restitution = config.getRammingRestitution();
        // Coupled convoy momentum factor scales with total convoy mass so heavier cargo delivers higher impact energy
        float massMomentumScale = 1.0f + ((totalConvoyMass - config.getLocomotiveBaseMassTons()) / (config.getLocomotiveBaseMassTons() * 2.0f));
        float impulseMagnitude = reducedMass * (1.0f + restitution) * closingSpeed * massMomentumScale;
        float convoyMomentum = totalConvoyMass * Math.max(closingSpeed, forwardSpeedMps);

        // Apply knockback impulse to obstacle body
        scratchForce.set(scratchImpulse).scl(impulseMagnitude);
        obstacleBody.applyLinearImpulse(scratchForce, obstacleBody.getWorldCenter(), true);

        // Apply momentum-conserving speed loss to the convoy (lighter convoys lose more speed on impact than heavy convoys)
        float speedLoss = Math.min(forwardSpeedMps, impulseMagnitude / totalConvoyMass);
        forwardSpeedMps = Math.max(0.0f, forwardSpeedMps - speedLoss);

        RammingCollisionEvent event = rammingEventPool.obtain();
        event.tickIndex = completedTicks;
        event.targetId = targetId != null ? targetId : "obstacle";
        event.convoyMassTons = totalConvoyMass;
        event.targetMassTons = effectiveObstacleMass;
        event.closingSpeedMps = closingSpeed;
        event.impulseMagnitudeNs = impulseMagnitude;
        event.convoyMomentumTransfer = convoyMomentum;
        event.hullDamageDealt = impulseMagnitude * config.getRammingDamagePerImpulseUnit();
        event.convoySpeedLossMps = speedLoss;
        event.contactPointWorld.set(obstacleBody.getPosition());
        event.impulseNormal.set(scratchImpulse);

        activeRammingEvents.add(event);
        return event;
    }

    /**
     * Frees all active RammingCollisionEvents back into the Pool so zero garbage is produced across frames.
     */
    public void clearAndFreeRammingEvents() {
        rammingEventPool.freeAll(activeRammingEvents);
        activeRammingEvents.clear();
    }

    public Array<RammingCollisionEvent> getActiveRammingEvents() {
        return activeRammingEvents;
    }

    public int getFreeRammingEventPoolCount() {
        return rammingEventPool.getFree();
    }

    /**
     * Updates the dynamic cargo mass (in tons) carried by a specific carriage index [0 .. carriageCount-1].
     */
    public void setCarriageCargoMass(int carriageIndex, float cargoMassTons) {
        if (carriageIndex < 0 || carriageIndex >= carriages.size()) {
            throw new IndexOutOfBoundsException("Invalid carriageIndex: " + carriageIndex);
        }
        carriages.get(carriageIndex).setCargoMassTons(cargoMassTons);
    }

    /**
     * Distributes a total cargo mass (in tons) evenly across all attached carriages.
     */
    public void setTotalCargoMassTons(float totalCargoMassTons) {
        float clamped = Math.max(0.0f, totalCargoMassTons);
        float perCarriage = clamped / carriages.size();
        for (int i = 0; i < carriages.size(); i++) {
            carriages.get(i).setCargoMassTons(perCarriage);
        }
    }

    public float getTotalCargoMassTons() {
        float sum = 0.0f;
        for (int i = 0; i < carriages.size(); i++) {
            sum += carriages.get(i).getCargoMassTons();
        }
        return sum;
    }

    public float getTotalConvoyMassTons() {
        float total = locomotiveBody.getMass();
        for (int i = 0; i < carriages.size(); i++) {
            total += carriages.get(i).getTotalMassTons();
        }
        return total;
    }

    /**
     * Computes effective braking force (Newtons) incorporating DES-01 per-ton braking friction.
     * Note: While each ton of cargo adds auxiliary brake shoe friction (brakingFrictionPerTon),
     * total inertia grows faster than brake friction, so heavier convoys still decelerate more slowly
     * and require a longer braking distance to stop.
     */
    public float computeEffectiveBrakingForceNewtons() {
        float baseMass = config.getLocomotiveBaseMassTons() + (carriages.size() * config.getCarriageBaseMassTons());
        float extraCargoTons = Math.max(0.0f, getTotalConvoyMassTons() - baseMass);
        // Auxiliary carriage brake shoes add partial braking force per ton, while inertia scales 1:1 with mass
        return config.getBaseBrakingForceNewtons() + (extraCargoTons * (config.getBrakingFrictionPerTon() * 0.25f));
    }

    public float computeInstantaneousBrakingDecelerationMps2() {
        return computeEffectiveBrakingForceNewtons() / getTotalConvoyMassTons();
    }

    public void setThrottleInput(float throttleInput) {
        this.throttleInput = MathUtils.clamp(throttleInput, 0.0f, 1.0f);
    }

    public float getThrottleInput() {
        return throttleInput;
    }

    public void setBrakeInput(float brakeInput) {
        this.brakeInput = MathUtils.clamp(brakeInput, 0.0f, 1.0f);
    }

    public float getBrakeInput() {
        return brakeInput;
    }

    public void setThrusterPowerMultiplier(float thrusterPowerMultiplier) {
        this.thrusterPowerMultiplier = Math.max(0.0f, thrusterPowerMultiplier);
    }

    public float getThrusterPowerMultiplier() {
        return thrusterPowerMultiplier;
    }

    public void setForwardSpeedMps(float forwardSpeedMps) {
        this.forwardSpeedMps = Math.max(0.0f, forwardSpeedMps);
        TrackSample sample = trackSystem.obtainSample();
        try {
            trackSystem.sampleAtDistance(distanceAlongTrackMeters, sample);
            locomotiveBody.setLinearVelocity(sample.tangent.x * this.forwardSpeedMps, sample.tangent.y * this.forwardSpeedMps);
        } finally {
            trackSystem.freeSample(sample);
        }
    }

    public float getForwardSpeedMps() {
        return forwardSpeedMps;
    }

    public float getDistanceAlongTrackMeters() {
        return distanceAlongTrackMeters;
    }

    public long getCompletedTicks() {
        return completedTicks;
    }

    public World getWorld() {
        return world;
    }

    public Body getLocomotiveBody() {
        return locomotiveBody;
    }

    public List<CarriageUnit> getCarriages() {
        return carriages;
    }

    public List<RevoluteJoint> getRevoluteJoints() {
        return revoluteJoints;
    }

    public SplineTrackSystem getTrackSystem() {
        return trackSystem;
    }

    @Override
    public void dispose() {
        if (!disposed) {
            clearAndFreeRammingEvents();
            world.dispose();
            disposed = true;
        }
    }

    public void close() { dispose(); }

    public boolean isDisposed() {
        return disposed;
    }
}
