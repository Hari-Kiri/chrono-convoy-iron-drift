package com.chronoconvoy.qa;

import com.badlogic.gdx.math.CatmullRomSpline;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJointDef;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic Headless Box2D multi-car convoy and raider collision simulation harness.
 * Verifies:
 * 1. Zero NaN / Infinity positions, angles, or velocities across multi-car articulated chains.
 * 2. Zero barrier tunneling under high-velocity raider ramming and emergency braking with CCD (bullet=true).
 * 3. Bit-exact deterministic replay consistency across fixed-timestep (1/60s) ticks.
 */
public final class ConvoyPhysicsHarness implements Disposable, AutoCloseable {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f;
    public static final int VELOCITY_ITERATIONS = 8;
    public static final int POSITION_ITERATIONS = 3;
    public static final float MAX_ACCUMULATOR = 0.25f;

    public static final float ARENA_HALF_WIDTH = 250.0f;
    public static final float ARENA_HALF_HEIGHT = 120.0f;
    public static final float WALL_THICKNESS = 4.0f;

    private final World world;
    private final List<Body> convoyCars = new ArrayList<>();
    private final List<Joint> couplerJoints = new ArrayList<>();
    private final List<Body> raiderBodies = new ArrayList<>();
    private final List<Body> barrierBodies = new ArrayList<>();
    private final CatmullRomSpline<Vector2> trackSpline;

    private int totalTicks = 0;
    private int collisionEvents = 0;
    private float maxRecordedSpeed = 0.0f;
    private float maxNormalImpulse = 0.0f;
    private boolean nanOrInfiniteDetected = false;
    private boolean tunnelingDetected = false;
    private boolean disposed = false;

    public record TelemetrySnapshot(
            int totalTicks,
            int carCount,
            int couplerCount,
            int raiderCount,
            int collisionEvents,
            float maxRecordedSpeed,
            float maxNormalImpulse,
            boolean nanOrInfiniteDetected,
            boolean tunnelingDetected,
            String deterministicStateHash
    ) {}

    public ConvoyPhysicsHarness(int numCarriages) {
        HeadlessGdxBootstrap.init();
        this.world = new World(new Vector2(0.0f, 0.0f), true);
        World.setVelocityThreshold(1.0f);
        this.trackSpline = createDefaultSpline();
        installContactListener();
        buildPerimeterBarriers();
        buildArticulatedConvoy(numCarriages);
    }

    private CatmullRomSpline<Vector2> createDefaultSpline() {
        Vector2[] controlPoints = new Vector2[] {
                new Vector2(-220f, 0f),
                new Vector2(-180f, 0f),
                new Vector2(-100f, 18f),
                new Vector2(0f, -15f),
                new Vector2(100f, 20f),
                new Vector2(180f, 0f),
                new Vector2(220f, 0f)
        };
        return new CatmullRomSpline<>(controlPoints, false);
    }

    private void installContactListener() {
        world.setContactListener(new ContactListener() {
            @Override
            public void beginContact(Contact contact) {
                collisionEvents++;
            }

            @Override
            public void endContact(Contact contact) {
            }

            @Override
            public void preSolve(Contact contact, Manifold oldManifold) {
            }

            @Override
            public void postSolve(Contact contact, ContactImpulse impulse) {
                if (impulse != null && impulse.getNormalImpulses() != null) {
                    for (int i = 0; i < impulse.getCount(); i++) {
                        float imp = impulse.getNormalImpulses()[i];
                        if (!Float.isFinite(imp)) {
                            nanOrInfiniteDetected = true;
                        } else if (imp > maxNormalImpulse) {
                            maxNormalImpulse = imp;
                        }
                    }
                }
            }
        });
    }

    private void buildPerimeterBarriers() {
        // North, South, East, West impenetrable canyon barriers
        barrierBodies.add(createStaticWall(0.0f, ARENA_HALF_HEIGHT + WALL_THICKNESS, ARENA_HALF_WIDTH + WALL_THICKNESS * 2f, WALL_THICKNESS));
        barrierBodies.add(createStaticWall(0.0f, -ARENA_HALF_HEIGHT - WALL_THICKNESS, ARENA_HALF_WIDTH + WALL_THICKNESS * 2f, WALL_THICKNESS));
        barrierBodies.add(createStaticWall(ARENA_HALF_WIDTH + WALL_THICKNESS, 0.0f, WALL_THICKNESS, ARENA_HALF_HEIGHT + WALL_THICKNESS * 2f));
        barrierBodies.add(createStaticWall(-ARENA_HALF_WIDTH - WALL_THICKNESS, 0.0f, WALL_THICKNESS, ARENA_HALF_HEIGHT + WALL_THICKNESS * 2f));
    }

    public Body createStaticWall(float centerX, float centerY, float halfWidth, float halfHeight) {
        BodyDef def = new BodyDef();
        def.type = BodyDef.BodyType.StaticBody;
        def.position.set(centerX, centerY);
        Body wall = world.createBody(def);

        PolygonShape shape = new PolygonShape();
        shape.setAsBox(halfWidth, halfHeight);

        FixtureDef fixture = new FixtureDef();
        fixture.shape = shape;
        fixture.friction = 0.4f;
        fixture.restitution = 0.2f;
        wall.createFixture(fixture);
        shape.dispose();
        wall.setUserData("BARRIER");
        return wall;
    }

    private void buildArticulatedConvoy(int numCarriages) {
        int totalCars = Math.max(1, 1 + numCarriages); // 1 Locomotive + N Carriages
        float carHalfLength = 4.0f;
        float carHalfWidth = 1.6f;
        float couplerGap = 1.2f;
        float spacing = (carHalfLength * 2.0f) + couplerGap;
        float startX = -120.0f;

        for (int i = 0; i < totalCars; i++) {
            boolean isLocomotive = (i == 0);
            BodyDef def = new BodyDef();
            def.type = BodyDef.BodyType.DynamicBody;
            def.position.set(startX - (i * spacing), 0.0f);
            def.bullet = true; // Continuous Collision Detection (CCD) mandatory for train cars
            def.linearDamping = 0.15f;
            def.angularDamping = 0.85f;

            Body car = world.createBody(def);
            PolygonShape box = new PolygonShape();
            box.setAsBox(carHalfLength, carHalfWidth);

            FixtureDef fixture = new FixtureDef();
            fixture.shape = box;
            fixture.density = isLocomotive ? 12.0f : 8.0f;
            fixture.friction = 0.35f;
            fixture.restitution = 0.15f;

            car.createFixture(fixture);
            box.dispose();
            car.setUserData(isLocomotive ? "LOCOMOTIVE" : "CARRIAGE_" + i);
            convoyCars.add(car);

            if (i > 0) {
                Body frontCar = convoyCars.get(i - 1);
                float anchorX = frontCar.getPosition().x - carHalfLength - (couplerGap * 0.5f);

                RevoluteJointDef revDef = new RevoluteJointDef();
                revDef.initialize(frontCar, car, new Vector2(anchorX, 0.0f));
                revDef.collideConnected = true;
                revDef.enableLimit = true;
                revDef.lowerAngle = (float) Math.toRadians(-38.0);
                revDef.upperAngle = (float) Math.toRadians(38.0);
                couplerJoints.add(world.createJoint(revDef));

                DistanceJointDef distDef = new DistanceJointDef();
                distDef.initialize(
                        frontCar,
                        car,
                        new Vector2(frontCar.getPosition().x - carHalfLength, 0.0f),
                        new Vector2(car.getPosition().x + carHalfLength, 0.0f)
                );
                distDef.collideConnected = true;
                distDef.dampingRatio = 0.75f;
                distDef.frequencyHz = 12.0f;
                couplerJoints.add(world.createJoint(distDef));
            }
        }
    }

    /**
     * Spawns a high-velocity armored raider interceptor aimed at the convoy or a barrier.
     */
    public Body spawnRaider(float x, float y, float vx, float vy, float density, boolean ccdBullet) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(density) || density <= 0.0f) {
            throw new IllegalArgumentException("Raider spawn parameters must be finite and density > 0");
        }
        BodyDef def = new BodyDef();
        def.type = BodyDef.BodyType.DynamicBody;
        def.position.set(x, y);
        def.bullet = ccdBullet;
        def.linearDamping = 0.05f;
        def.angularDamping = 0.4f;

        Body raider = world.createBody(def);
        PolygonShape shape = new PolygonShape();
        shape.setAsBox(2.2f, 1.1f);

        FixtureDef fixture = new FixtureDef();
        fixture.shape = shape;
        fixture.density = density;
        fixture.friction = 0.25f;
        fixture.restitution = 0.25f;
        raider.createFixture(fixture);
        shape.dispose();

        raider.setLinearVelocity(vx, vy);
        raider.setUserData("RAIDER_" + raiderBodies.size());
        raiderBodies.add(raider);
        return raider;
    }

    /**
     * Applies spline guidance traction force to the locomotive based on propulsion watts.
     */
    public void applyLocomotiveSplineTraction(float allocatedPropulsionWatts) {
        if (!Float.isFinite(allocatedPropulsionWatts) || allocatedPropulsionWatts < 0.0f) {
            throw new IllegalArgumentException("Propulsion watts must be finite and non-negative");
        }
        Body loco = convoyCars.get(0);
        Vector2 pos = loco.getPosition();

        // Approximate normalized spline parameter u in [0, 1] from x coordinate
        float u = Math.clamp((pos.x + 180.0f) / 360.0f, 0.02f, 0.98f);
        Vector2 targetPoint = new Vector2();
        Vector2 tangent = new Vector2();
        trackSpline.valueAt(targetPoint, u);
        trackSpline.derivativeAt(tangent, u).nor();

        float tractionForceMagnitude = Math.min(allocatedPropulsionWatts * 35.0f, 45000.0f);
        Vector2 lateralError = new Vector2(targetPoint).sub(pos);
        Vector2 guidanceForce = new Vector2(tangent).scl(tractionForceMagnitude)
                .add(lateralError.scl(1800.0f));

        // Clamp locomotive speed below stability ceiling (65 m/s)
        if (loco.getLinearVelocity().len() < 65.0f) {
            loco.applyForceToCenter(guidanceForce, true);
        }
    }

    /**
     * Steps the simulation forward deterministically by a single fixed tick (1/60s)
     * and audits all bodies and joints for NaN/Infinity and barrier tunneling.
     */
    public void stepSingleTick() {
        if (disposed) {
            throw new IllegalStateException("Cannot step a disposed ConvoyPhysicsHarness");
        }
        world.step(FIXED_TIMESTEP, VELOCITY_ITERATIONS, POSITION_ITERATIONS);
        totalTicks++;
        auditWorldInvariants();
    }

    /**
     * Steps the simulation for N fixed ticks while applying spline traction.
     */
    public void stepTicksWithTraction(int ticks, float propulsionWatts) {
        for (int i = 0; i < ticks; i++) {
            applyLocomotiveSplineTraction(propulsionWatts);
            stepSingleTick();
        }
    }

    private void auditWorldInvariants() {
        Array<Body> bodies = new Array<>();
        world.getBodies(bodies);
        for (int i = 0; i < bodies.size; i++) {
            Body body = bodies.get(i);
            Vector2 pos = body.getPosition();
            Vector2 vel = body.getLinearVelocity();
            float angle = body.getAngle();
            float omega = body.getAngularVelocity();

            if (!Float.isFinite(pos.x) || !Float.isFinite(pos.y)
                    || !Float.isFinite(vel.x) || !Float.isFinite(vel.y)
                    || !Float.isFinite(angle) || !Float.isFinite(omega)) {
                nanOrInfiniteDetected = true;
                continue;
            }

            if (body.getType() == BodyDef.BodyType.DynamicBody) {
                float speed = vel.len();
                if (speed > maxRecordedSpeed) {
                    maxRecordedSpeed = speed;
                }
                // Check strict containment inside static perimeter walls
                if (Math.abs(pos.x) > ARENA_HALF_WIDTH || Math.abs(pos.y) > ARENA_HALF_HEIGHT) {
                    tunnelingDetected = true;
                }
            }
        }

        for (Joint joint : couplerJoints) {
            Vector2 anchorA = joint.getAnchorA();
            Vector2 anchorB = joint.getAnchorB();
            Vector2 reactionForce = joint.getReactionForce(1.0f / FIXED_TIMESTEP);
            float reactionTorque = joint.getReactionTorque(1.0f / FIXED_TIMESTEP);
            if (!Float.isFinite(anchorA.x) || !Float.isFinite(anchorA.y)
                    || !Float.isFinite(anchorB.x) || !Float.isFinite(anchorB.y)
                    || !Float.isFinite(reactionForce.x) || !Float.isFinite(reactionForce.y)
                    || !Float.isFinite(reactionTorque)) {
                nanOrInfiniteDetected = true;
            }
        }
    }

    public String computeDeterministicStateHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append("ticks=").append(totalTicks).append(';');
            for (int i = 0; i < convoyCars.size(); i++) {
                Body car = convoyCars.get(i);
                Vector2 p = car.getPosition();
                Vector2 v = car.getLinearVelocity();
                sb.append(String.format(Locale.ROOT,
                        "car[%d]=(%.5f,%.5f,%.5f,%.5f,%.5f,%.5f);",
                        i, p.x, p.y, car.getAngle(), v.x, v.y, car.getAngularVelocity()));
            }
            for (int i = 0; i < raiderBodies.size(); i++) {
                Body r = raiderBodies.get(i);
                Vector2 p = r.getPosition();
                Vector2 v = r.getLinearVelocity();
                sb.append(String.format(Locale.ROOT,
                        "raider[%d]=(%.5f,%.5f,%.5f,%.5f,%.5f,%.5f);",
                        i, p.x, p.y, r.getAngle(), v.x, v.y, r.getAngularVelocity()));
            }
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256 state hash", e);
        }
    }

    public TelemetrySnapshot snapshot() {
        return new TelemetrySnapshot(
                totalTicks,
                convoyCars.size(),
                couplerJoints.size(),
                raiderBodies.size(),
                collisionEvents,
                maxRecordedSpeed,
                maxNormalImpulse,
                nanOrInfiniteDetected,
                tunnelingDetected,
                computeDeterministicStateHash()
        );
    }

    public List<Body> getConvoyCars() {
        return Collections.unmodifiableList(convoyCars);
    }

    public List<Body> getRaiderBodies() {
        return Collections.unmodifiableList(raiderBodies);
    }

    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void close() {
        dispose();
    }

    @Override
    public void dispose() {
        if (!disposed) {
            world.dispose();
            disposed = true;
        }
    }
}
