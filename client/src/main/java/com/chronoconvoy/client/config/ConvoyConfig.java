package com.chronoconvoy.client.config;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Data-driven configuration schema for Chrono-Convoy: Iron Drift (Sprint 1 DES-01 / CLI-01 / CLI-02).
 * Loads tunables from docs/design/convoy_config.json or client/assets/config/convoy_config.json when available,
 * falling back to deterministic Sprint 1 baseline values.
 */
public final class ConvoyConfig {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f;
    public static final int VELOCITY_ITERATIONS = 8;
    public static final int POSITION_ITERATIONS = 3;
    public static final float PIXELS_PER_METER = 32.0f;
    public static final float METERS_PER_PIXEL = 1.0f / PIXELS_PER_METER;

    private String schemaId = "chrono-convoy-iron-drift-config-v1";
    private String version = "1.0.0-sprint1";
    private long defaultDeterministicSeed = 42069L;

    // Locomotive & Multi-Car Physics Tunables (CLI-01 / DES-01)
    private float locomotiveBaseMassTons = 25.0f;
    private float carriageBaseMassTons = 9.5f;
    private int defaultCarriageCount = 3;
    private float baseThrustForceNewtons = 1200.0f;
    private float baseBrakingForceNewtons = 1400.0f;
    private float brakingFrictionPerTon = 14.0f;
    private float rollingResistanceCoeff = 0.018f;
    private float maxCruisingSpeedMps = 38.0f;
    private float carriageSpacingMeters = 10.5f;
    private float revoluteJointLimitRadians = 0.41887903f; // 24 degrees per DES-01
    private float rammingRestitution = 0.35f;
    private float rammingProwMultiplier = 1.35f;
    private float rammingDamagePerImpulseUnit = 0.25f;

    // Continuous Spline Track Tunables (CLI-01 / DES-01)
    private float trackSegmentLengthMeters = 120.0f;
    private float lateralGuidanceStiffness = 18.0f;
    private float maxLateralDriftMeters = 6.5f;
    private float forkLateralOffsetMeters = 18.0f;

    // Reactor Watt Power-Routing & Thermal Tunables (CLI-02 / DES-01 / DES-02)
    private float maxReactorWatts = 100.0f;
    private float overclockMaxReactorWatts = 130.0f;
    private float subsystemMaxWatts = 60.0f;
    private float defaultShieldsWatts = 30.0f;
    private float defaultArtilleryWatts = 35.0f;
    private float defaultThrustersWatts = 35.0f;
    private float maxHeatCapacity = 100.0f;
    private float thermalWarningThreshold = 75.0f;
    private float cooldownRecoveryThreshold = 35.0f;
    private float safeThermalLoadWatts = 85.0f;
    private float heatGenerationPerExcessWattSec = 0.85f;
    private float heatPerArtilleryShot = 1.15f;
    private float passiveCoolingPerSecond = 8.0f;
    private float coolingRodHeatVentAmount = 45.0f;
    private int initialCoolingRods = 3;

    // Turret & Shield Dome Tunables (CLI-02 / DES-01)
    private float baseTurretFireRateHz = 4.0f;
    private float maxTurretFireRateHz = 12.0f;
    private float baseProjectileDamage = 18.0f;
    private float projectileSpeedMps = 110.0f;
    private float turretRangeMeters = 85.0f;
    private float baseShieldCapacity = 400.0f;
    private float maxShieldCapacity = 900.0f;
    private float maxShieldRegenPerSec = 25.0f;

    // Raider Archetype Tunables (DES-01)
    private float rammerBuggyMassTons = 9.4f;
    private float rammerBuggyMaxHp = 120.0f;
    private float rammerBuggySpeedMps = 31.5f;
    private float mortarSkiffMassTons = 3.6f;
    private float mortarSkiffMaxHp = 160.0f;
    private float mortarSkiffSpeedMps = 30.0f;
    private float mortarSkiffShellDamage = 38.0f;
    private float mortarSkiffFireIntervalSec = 2.5f;

    public ConvoyConfig() {
    }

    /**
     * Loads "../docs/design/convoy_config.json" or "assets/config/convoy_config.json"
     * if present on disk, merging any defined fields over the defaults.
     */
    public static ConvoyConfig loadDefault() {
        ConvoyConfig config = new ConvoyConfig();
        String[] candidatePaths = {
            "assets/config/convoy_config.json",
            "../docs/design/convoy_config.json",
            "docs/design/convoy_config.json",
            "/workspace/chrono-convoy-iron-drift/docs/design/convoy_config.json"
        };
        for (String path : candidatePaths) {
            File file = new File(path);
            if (file.exists() && file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    String jsonText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    config.applyJsonOverrides(jsonText);
                    break;
                } catch (Exception ignored) {
                }
            }
        }
        return config;
    }

    public void applyJsonOverrides(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return;
        }
        JsonValue root = new JsonReader().parse(jsonText);
        if (root == null) {
            return;
        }

        if (root.has("schema_id")) {
            schemaId = root.getString("schema_id", schemaId);
        }
        if (root.has("version")) {
            version = root.getString("version", version);
        }
        if (root.has("deterministic_seed_default")) {
            defaultDeterministicSeed = root.getLong("deterministic_seed_default", defaultDeterministicSeed);
        }

        JsonValue loco = findSection(root, "locomotive", "physics", "convoy");
        if (loco != null) {
            if (loco.has("dry_mass_kg")) {
                locomotiveBaseMassTons = Math.max(1.0f, loco.getFloat("dry_mass_kg") / 1000.0f);
            } else {
                locomotiveBaseMassTons = getPositiveFloat(loco, "locomotiveBaseMassTons", locomotiveBaseMassTons);
            }
            if (loco.has("ramming_prow_multiplier")) {
                rammingProwMultiplier = getPositiveFloat(loco, "ramming_prow_multiplier", rammingProwMultiplier);
            }
            JsonValue coupler = loco.get("box2d_coupler_joint");
            if (coupler != null && coupler.has("angular_limit_deg")) {
                revoluteJointLimitRadians = (float) Math.toRadians(coupler.getFloat("angular_limit_deg"));
            }
        }

        JsonValue curves = root.get("mass_to_momentum_curves");
        if (curves != null) {
            rollingResistanceCoeff = getPositiveFloat(curves, "rolling_friction_coeff_mu", rollingResistanceCoeff);
        }

        JsonValue reactor = findSection(root, "reactor", "power", "wattAllocation");
        if (reactor != null) {
            maxReactorWatts = getPositiveFloat(reactor, "total_watts_pool",
                    getPositiveFloat(reactor, "maxReactorWatts", maxReactorWatts));
            overclockMaxReactorWatts = getPositiveFloat(reactor, "overclock_max_watts_pool", overclockMaxReactorWatts);
            JsonValue defaults = reactor.get("default_allocation_watts");
            if (defaults != null) {
                defaultShieldsWatts = getPositiveFloat(defaults, "shields", defaultShieldsWatts);
                defaultArtilleryWatts = getPositiveFloat(defaults, "artillery", defaultArtilleryWatts);
                defaultThrustersWatts = getPositiveFloat(defaults, "thrusters", defaultThrustersWatts);
            }
            JsonValue overclock = reactor.get("overclock");
            if (overclock != null) {
                coolingRodHeatVentAmount = getPositiveFloat(overclock, "cooling_rod_flush_heat_reduction", coolingRodHeatVentAmount);
                thermalWarningThreshold = getPositiveFloat(overclock, "thermal_warning_threshold", thermalWarningThreshold);
                maxHeatCapacity = getPositiveFloat(overclock, "thermal_shutdown_threshold", maxHeatCapacity);
            }
        }

        JsonValue splineTrack = root.get("spline_track");
        if (splineTrack != null) {
            trackSegmentLengthMeters = getPositiveFloat(splineTrack, "segment_length_m", trackSegmentLengthMeters);
            maxLateralDriftMeters = getPositiveFloat(splineTrack, "track_width_m", maxLateralDriftMeters);
        }
    }

    private static JsonValue findSection(JsonValue root, String... keys) {
        for (String key : keys) {
            if (root.has(key) && root.get(key).isObject()) {
                return root.get(key);
            }
        }
        return root;
    }

    private static float getPositiveFloat(JsonValue node, String key, float fallback) {
        if (node != null && node.has(key)) {
            try {
                float val = node.getFloat(key);
                return val > 0.0f ? val : fallback;
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public String getSchemaId() { return schemaId; }
    public String getVersion() { return version; }
    public long getDefaultDeterministicSeed() { return defaultDeterministicSeed; }

    public float getLocomotiveBaseMassTons() { return locomotiveBaseMassTons; }
    public void setLocomotiveBaseMassTons(float locomotiveBaseMassTons) { this.locomotiveBaseMassTons = locomotiveBaseMassTons; }

    public float getCarriageBaseMassTons() { return carriageBaseMassTons; }
    public void setCarriageBaseMassTons(float carriageBaseMassTons) { this.carriageBaseMassTons = carriageBaseMassTons; }

    public int getDefaultCarriageCount() { return defaultCarriageCount; }
    public void setDefaultCarriageCount(int defaultCarriageCount) { this.defaultCarriageCount = defaultCarriageCount; }

    public float getBaseThrustForceNewtons() { return baseThrustForceNewtons; }
    public void setBaseThrustForceNewtons(float baseThrustForceNewtons) { this.baseThrustForceNewtons = baseThrustForceNewtons; }

    public float getBaseBrakingForceNewtons() { return baseBrakingForceNewtons; }
    public void setBaseBrakingForceNewtons(float baseBrakingForceNewtons) { this.baseBrakingForceNewtons = baseBrakingForceNewtons; }

    public float getBrakingFrictionPerTon() { return brakingFrictionPerTon; }
    public void setBrakingFrictionPerTon(float brakingFrictionPerTon) { this.brakingFrictionPerTon = brakingFrictionPerTon; }

    public float getRollingResistanceCoeff() { return rollingResistanceCoeff; }
    public float getMaxCruisingSpeedMps() { return maxCruisingSpeedMps; }
    public float getCarriageSpacingMeters() { return carriageSpacingMeters; }
    public float getRevoluteJointLimitRadians() { return revoluteJointLimitRadians; }
    public float getRammingRestitution() { return rammingRestitution; }
    public float getRammingProwMultiplier() { return rammingProwMultiplier; }
    public float getRammingDamagePerImpulseUnit() { return rammingDamagePerImpulseUnit; }

    public float getTrackSegmentLengthMeters() { return trackSegmentLengthMeters; }
    public float getLateralGuidanceStiffness() { return lateralGuidanceStiffness; }
    public float getMaxLateralDriftMeters() { return maxLateralDriftMeters; }
    public float getForkLateralOffsetMeters() { return forkLateralOffsetMeters; }

    public float getMaxReactorWatts() { return maxReactorWatts; }
    public float getOverclockMaxReactorWatts() { return overclockMaxReactorWatts; }
    public float getSubsystemMaxWatts() { return subsystemMaxWatts; }
    public float getDefaultShieldsWatts() { return defaultShieldsWatts; }
    public float getDefaultArtilleryWatts() { return defaultArtilleryWatts; }
    public float getDefaultThrustersWatts() { return defaultThrustersWatts; }
    public float getMaxHeatCapacity() { return maxHeatCapacity; }
    public float getThermalWarningThreshold() { return thermalWarningThreshold; }
    public float getCooldownRecoveryThreshold() { return cooldownRecoveryThreshold; }
    public float getSafeThermalLoadWatts() { return safeThermalLoadWatts; }
    public float getHeatGenerationPerExcessWattSec() { return heatGenerationPerExcessWattSec; }
    public float getHeatPerArtilleryShot() { return heatPerArtilleryShot; }
    public float getPassiveCoolingPerSecond() { return passiveCoolingPerSecond; }
    public float getCoolingRodHeatVentAmount() { return coolingRodHeatVentAmount; }
    public int getInitialCoolingRods() { return initialCoolingRods; }

    public float getBaseTurretFireRateHz() { return baseTurretFireRateHz; }
    public float getMaxTurretFireRateHz() { return maxTurretFireRateHz; }
    public float getBaseProjectileDamage() { return baseProjectileDamage; }
    public float getProjectileSpeedMps() { return projectileSpeedMps; }
    public float getTurretRangeMeters() { return turretRangeMeters; }
    public float getBaseShieldCapacity() { return baseShieldCapacity; }
    public float getMaxShieldCapacity() { return maxShieldCapacity; }
    public float getMaxShieldRegenPerSec() { return maxShieldRegenPerSec; }

    public float getRammerBuggyMassTons() { return rammerBuggyMassTons; }
    public float getRammerBuggyMaxHp() { return rammerBuggyMaxHp; }
    public float getRammerBuggySpeedMps() { return rammerBuggySpeedMps; }
    public float getMortarSkiffMassTons() { return mortarSkiffMassTons; }
    public float getMortarSkiffMaxHp() { return mortarSkiffMaxHp; }
    public float getMortarSkiffSpeedMps() { return mortarSkiffSpeedMps; }
    public float getMortarSkiffShellDamage() { return mortarSkiffShellDamage; }
    public float getMortarSkiffFireIntervalSec() { return mortarSkiffFireIntervalSec; }
}
