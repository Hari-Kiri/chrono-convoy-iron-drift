package com.chronoconvoy.client.combat;

import com.badlogic.gdx.math.MathUtils;
import com.chronoconvoy.client.config.ConvoyConfig;

import java.util.Objects;

/**
 * [CLI-02] Reactor Watt Power-Routing Controller.
 * Allocates the 100 Reactor Watt budget across Shields (Shield Dome), Artillery (Gatling Turret),
 * and Thrusters (Locomotive Drive), managing thermal generation, overheat lockout, passive cooldown,
 * and Reactor Cooling Rod emergency thermal sinks (DES-02).
 */
public final class ReactorPowerController {

    public static final float MAX_REACTOR_WATTS = 100.0f;
    private static final float BUDGET_EPSILON = 1e-3f;

    private final ConvoyConfig config;

    private float shieldsWatts;
    private float artilleryWatts;
    private float thrustersWatts;

    private float currentHeat = 0.0f;
    private boolean overheated = false;
    private int overheatCount = 0;

    private int coolingRodsRemaining;
    private int coolingRodsConsumed = 0;

    private float currentShieldHp;

    public ReactorPowerController(ConvoyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.coolingRodsRemaining = config.getInitialCoolingRods();
        allocateWatts(
            config.getDefaultShieldsWatts(),
            config.getDefaultArtilleryWatts(),
            config.getDefaultThrustersWatts()
        );
        this.currentShieldHp = getShieldMaxCapacity();
    }

    /**
     * Strictly validates and allocates Reactor Watts across Shields, Artillery, and Thrusters.
     * Throws IllegalArgumentException if any subsystem is negative or if the sum exceeds 100 Reactor Watts.
     */
    public void allocateWatts(float shieldsWatts, float artilleryWatts, float thrustersWatts) {
        if (Float.isNaN(shieldsWatts) || Float.isNaN(artilleryWatts) || Float.isNaN(thrustersWatts)) {
            throw new IllegalArgumentException("Reactor Watt allocations must not be NaN");
        }
        if (shieldsWatts < -BUDGET_EPSILON || artilleryWatts < -BUDGET_EPSILON || thrustersWatts < -BUDGET_EPSILON) {
            throw new IllegalArgumentException("Reactor Watt allocations must be non-negative");
        }
        float clampedShields = Math.max(0.0f, shieldsWatts);
        float clampedArtillery = Math.max(0.0f, artilleryWatts);
        float clampedThrusters = Math.max(0.0f, thrustersWatts);
        float total = clampedShields + clampedArtillery + clampedThrusters;
        float maxBudget = config.getMaxReactorWatts();
        if (total > maxBudget + BUDGET_EPSILON) {
            throw new IllegalArgumentException(String.format(
                "Total allocated watts (%.2f W) exceeds reactor capacity (%.2f W)", total, maxBudget));
        }
        this.shieldsWatts = clampedShields;
        this.artilleryWatts = clampedArtillery;
        this.thrustersWatts = clampedThrusters;
        this.currentShieldHp = Math.min(this.currentShieldHp, getShieldMaxCapacity());
    }

    /**
     * Live slider helper for Scene2D Engineer Control Deck: normalizes three slider inputs so
     * their sum never exceeds the 100 Reactor Watt cap.
     */
    public void allocateClampedSliderWatts(float rawShields, float rawArtillery, float rawThrusters) {
        float s = Math.max(0.0f, rawShields);
        float a = Math.max(0.0f, rawArtillery);
        float t = Math.max(0.0f, rawThrusters);
        float sum = s + a + t;
        float maxBudget = config.getMaxReactorWatts();
        if (sum > maxBudget && sum > 0.0f) {
            float scale = maxBudget / sum;
            s *= scale;
            a *= scale;
            t *= scale;
        }
        this.shieldsWatts = s;
        this.artilleryWatts = a;
        this.thrustersWatts = t;
        this.currentShieldHp = Math.min(this.currentShieldHp, getShieldMaxCapacity());
    }

    /**
     * Updates reactor thermal state and shield dome regeneration over deltaSeconds.
     */
    public void update(float deltaSeconds) {
        if (deltaSeconds <= 0.0f) {
            return;
        }
        float totalWatts = getTotalAllocatedWatts();
        float safeLoad = config.getSafeThermalLoadWatts();

        if (overheated) {
            // During thermal lockout, emergency vents cool the core at 1.5x passive rate
            currentHeat = Math.max(0.0f, currentHeat - (config.getPassiveCoolingPerSecond() * 1.5f * deltaSeconds));
            if (currentHeat <= config.getCooldownRecoveryThreshold()) {
                overheated = false;
            }
        } else {
            if (totalWatts > safeLoad) {
                float excessWatts = totalWatts - safeLoad;
                // Overdriving a single subsystem above 50W also adds concentrated thermal stress
                float subsystemOverdrive = Math.max(0.0f, shieldsWatts - 50.0f)
                        + Math.max(0.0f, artilleryWatts - 50.0f)
                        + Math.max(0.0f, thrustersWatts - 50.0f);
                float heatRate = (excessWatts + subsystemOverdrive * 0.5f) * config.getHeatGenerationPerExcessWattSec();
                addThermalLoad(heatRate * deltaSeconds);
            } else {
                float coolFactor = 1.0f + ((safeLoad - totalWatts) / Math.max(1.0f, safeLoad)) * 0.5f;
                currentHeat = Math.max(0.0f, currentHeat - (config.getPassiveCoolingPerSecond() * coolFactor * deltaSeconds));
            }
        }

        // Regenerate Shield Dome based on current Shields watt allocation
        float maxShield = getShieldMaxCapacity();
        if (currentShieldHp < maxShield) {
            currentShieldHp = Math.min(maxShield, currentShieldHp + (getShieldRegenPerSecond() * deltaSeconds));
        }
    }

    /**
     * Adds direct thermal load (e.g., from sustained Gatling Turret bursts or thermal hazards).
     */
    public void addThermalLoad(float heatDelta) {
        if (heatDelta <= 0.0f) {
            return;
        }
        currentHeat = Math.min(config.getMaxHeatCapacity(), currentHeat + heatDelta);
        if (currentHeat >= config.getMaxHeatCapacity() - 1e-4f && !overheated) {
            currentHeat = config.getMaxHeatCapacity();
            overheated = true;
            overheatCount++;
        }
    }

    /**
     * Consumes one Reactor Cooling Rod (DES-02 economy sink) to immediately vent reactor heat.
     *
     * @return true if a Cooling Rod was available and consumed
     */
    public boolean consumeCoolingRod() {
        if (coolingRodsRemaining <= 0) {
            return false;
        }
        coolingRodsRemaining--;
        coolingRodsConsumed++;
        currentHeat = Math.max(0.0f, currentHeat - config.getCoolingRodHeatVentAmount());
        if (overheated && currentHeat <= config.getCooldownRecoveryThreshold()) {
            overheated = false;
        }
        return true;
    }

    public void addCoolingRods(int count) {
        if (count > 0) {
            coolingRodsRemaining += count;
        }
    }

    /**
     * Returns the live locomotive thrust multiplier driven by Thrusters watts.
     * Scales from 0.25x at 0W up to 1.85x at 100W (throttled to 0.25x limp mode when overheated).
     */
    public float getThrustMultiplier() {
        if (overheated) {
            return 0.25f;
        }
        float normalized = thrustersWatts / config.getMaxReactorWatts();
        return 0.25f + (1.60f * normalized);
    }

    /**
     * Returns the live Gatling Turret fire rate (shots/sec) driven by Artillery watts.
     * Locked out (0 Hz) during reactor overheat or when 0W is routed to Artillery.
     */
    public float getArtilleryFireRateHz() {
        if (overheated || artilleryWatts <= 0.01f) {
            return 0.0f;
        }
        float normalized = artilleryWatts / config.getMaxReactorWatts();
        return config.getBaseTurretFireRateHz()
                + (config.getMaxTurretFireRateHz() - config.getBaseTurretFireRateHz()) * normalized;
    }

    /**
     * Returns the projectile damage multiplier driven by Artillery watts.
     */
    public float getArtilleryDamageMultiplier() {
        if (overheated || artilleryWatts <= 0.01f) {
            return 0.0f;
        }
        float normalized = artilleryWatts / config.getMaxReactorWatts();
        return 0.75f + (1.25f * normalized);
    }

    /**
     * Returns the live Shield Dome maximum HP capacity driven by Shields watts.
     */
    public float getShieldMaxCapacity() {
        if (shieldsWatts <= 0.01f) {
            return 0.0f;
        }
        float normalized = shieldsWatts / config.getMaxReactorWatts();
        float capacity = config.getBaseShieldCapacity()
                + (config.getMaxShieldCapacity() - config.getBaseShieldCapacity()) * normalized;
        return overheated ? capacity * 0.4f : capacity;
    }

    /**
     * Returns the live Shield Dome regeneration rate (shield HP/sec) driven by Shields watts.
     */
    public float getShieldRegenPerSecond() {
        if (overheated || shieldsWatts <= 0.01f) {
            return 0.0f;
        }
        float normalized = shieldsWatts / config.getMaxReactorWatts();
        return config.getMaxShieldRegenPerSec() * normalized;
    }

    /**
     * Returns the fraction [0.0 .. 0.85] of incoming damage mitigated by the Shield Dome.
     */
    public float getShieldDamageMitigationRatio() {
        if (overheated || shieldsWatts <= 0.01f || currentShieldHp <= 0.0f) {
            return 0.0f;
        }
        float normalized = shieldsWatts / config.getMaxReactorWatts();
        return MathUtils.clamp(0.20f + (0.65f * normalized), 0.0f, 0.85f);
    }

    /**
     * Absorbs incoming raider damage (such as Mortar Skiff shells) using the Shield Dome
     * and returns any bleed-through damage that reaches the convoy hull.
     */
    public float absorbIncomingDamage(float rawDamage) {
        if (rawDamage <= 0.0f) {
            return 0.0f;
        }
        float mitigation = getShieldDamageMitigationRatio();
        float shieldDesiredAbsorption = rawDamage * mitigation;
        float actualAbsorbed = Math.min(currentShieldHp, shieldDesiredAbsorption);
        currentShieldHp -= actualAbsorbed;
        return rawDamage - actualAbsorbed;
    }

    public float getShieldsWatts() { return shieldsWatts; }
    public float getArtilleryWatts() { return artilleryWatts; }
    public float getThrustersWatts() { return thrustersWatts; }
    public float getTotalAllocatedWatts() { return shieldsWatts + artilleryWatts + thrustersWatts; }
    public float getUnallocatedWatts() { return Math.max(0.0f, config.getMaxReactorWatts() - getTotalAllocatedWatts()); }

    public float getCurrentHeat() { return currentHeat; }
    public boolean isOverheated() { return overheated; }
    public int getOverheatCount() { return overheatCount; }

    public int getCoolingRodsRemaining() { return coolingRodsRemaining; }
    public int getCoolingRodsConsumed() { return coolingRodsConsumed; }

    public float getCurrentShieldHp() { return currentShieldHp; }
}
