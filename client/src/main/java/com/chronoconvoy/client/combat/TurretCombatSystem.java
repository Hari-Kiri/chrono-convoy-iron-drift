package com.chronoconvoy.client.combat;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.chronoconvoy.client.config.ConvoyConfig;

import java.util.Objects;

/**
 * [CLI-02] Gatling Turret Combat Loop & Raider Engagement System.
 * Engages both DES-01 raider archetypes (RAMMER_BUGGY and MORTAR_SKIFF) using pooled
 * RaiderTarget and TurretProjectile objects (Pool/Poolable) with zero per-frame allocations.
 */
public final class TurretCombatSystem {

    public enum RaiderArchetype {
        RAMMER_BUGGY,
        MORTAR_SKIFF
    }

    /**
     * Poolable wasteland raider entity (Rammer Buggy or Mortar Skiff).
     */
    public static final class RaiderTarget implements Pool.Poolable {
        public String id = "";
        public RaiderArchetype archetype = RaiderArchetype.RAMMER_BUGGY;
        public final Vector2 position = new Vector2();
        public final Vector2 velocity = new Vector2();
        public float massTons;
        public float maxHp;
        public float currentHp;
        public float attackCooldownSec;
        public boolean active;

        public boolean isDestroyed() {
            return !active || currentHp <= 0.0f;
        }

        @Override
        public void reset() {
            id = "";
            archetype = RaiderArchetype.RAMMER_BUGGY;
            position.setZero();
            velocity.setZero();
            massTons = 0.0f;
            maxHp = 0.0f;
            currentHp = 0.0f;
            attackCooldownSec = 0.0f;
            active = false;
        }
    }

    /**
     * Poolable Gatling Turret projectile.
     */
    public static final class TurretProjectile implements Pool.Poolable {
        public final Vector2 position = new Vector2();
        public final Vector2 velocity = new Vector2();
        public String targetId = "";
        public float damage;
        public float remainingLifetimeSec;
        public boolean active;

        @Override
        public void reset() {
            position.setZero();
            velocity.setZero();
            targetId = "";
            damage = 0.0f;
            remainingLifetimeSec = 0.0f;
            active = false;
        }
    }

    private final ConvoyConfig config;
    private final ReactorPowerController reactorController;

    private final Pool<RaiderTarget> raiderPool = new Pool<RaiderTarget>(16, 64) {
        @Override
        protected RaiderTarget newObject() {
            return new RaiderTarget();
        }
    };

    private final Pool<TurretProjectile> projectilePool = new Pool<TurretProjectile>(64, 256) {
        @Override
        protected TurretProjectile newObject() {
            return new TurretProjectile();
        }
    };

    private final Array<RaiderTarget> activeRaiders = new Array<>(false, 16);
    private final Array<TurretProjectile> activeProjectiles = new Array<>(false, 64);

    // Pre-allocated scratch vectors for zero-allocation combat loop
    private final Vector2 scratchTurretPos = new Vector2();
    private final Vector2 scratchAimVector = new Vector2();
    private final Vector2 scratchLeadPos = new Vector2();

    private float fireCooldownSeconds = 0.0f;
    private float turretAimAngleRad = 0.0f;
    private int totalShotsFired = 0;
    private int totalHitsLanded = 0;
    private int rammerBuggiesDestroyed = 0;
    private int mortarSkiffsDestroyed = 0;
    private float convoyHullDamageTaken = 0.0f;

    public TurretCombatSystem(ConvoyConfig config, ReactorPowerController reactorController) {
        this.config = Objects.requireNonNull(config, "config");
        this.reactorController = Objects.requireNonNull(reactorController, "reactorController");
    }

    /**
     * Spawns a pooled RaiderTarget of the specified archetype (RAMMER_BUGGY or MORTAR_SKIFF).
     */
    public RaiderTarget spawnRaider(String id, RaiderArchetype archetype, float xMeters, float yMeters, float vxMps, float vyMps) {
        Objects.requireNonNull(archetype, "archetype");
        RaiderTarget raider = raiderPool.obtain();
        raider.id = id != null ? id : ("raider-" + (activeRaiders.size + 1));
        raider.archetype = archetype;
        raider.position.set(xMeters, yMeters);
        raider.velocity.set(vxMps, vyMps);
        if (archetype == RaiderArchetype.RAMMER_BUGGY) {
            raider.massTons = config.getRammerBuggyMassTons();
            raider.maxHp = config.getRammerBuggyMaxHp();
            raider.currentHp = config.getRammerBuggyMaxHp();
            raider.attackCooldownSec = 0.0f;
        } else {
            raider.massTons = config.getMortarSkiffMassTons();
            raider.maxHp = config.getMortarSkiffMaxHp();
            raider.currentHp = config.getMortarSkiffMaxHp();
            raider.attackCooldownSec = config.getMortarSkiffFireIntervalSec();
        }
        raider.active = true;
        activeRaiders.add(raider);
        return raider;
    }

    /**
     * Advances turret targeting, firing, projectile flight, and raider actions over deltaSeconds.
     */
    public void update(float deltaSeconds, Vector2 turretWorldPos, Vector2 convoyVelocityMps) {
        if (deltaSeconds <= 0.0f) {
            return;
        }
        scratchTurretPos.set(turretWorldPos);

        // 1. Update active raiders and process Mortar Skiff bombardment against Shield Dome
        updateRaiders(deltaSeconds, scratchTurretPos, convoyVelocityMps);

        // 2. Acquire highest-priority raider target within turret range
        RaiderTarget lockedTarget = selectPriorityTarget(scratchTurretPos);

        // 3. Fire Gatling Turret at rate governed by Artillery Watts (if not overheated)
        fireCooldownSeconds = Math.max(0.0f, fireCooldownSeconds - deltaSeconds);
        float fireRateHz = reactorController.getArtilleryFireRateHz();
        if (lockedTarget != null && fireRateHz > 0.0f) {
            float fireInterval = 1.0f / fireRateHz;
            while (fireCooldownSeconds <= 0.0f) {
                fireProjectileAt(scratchTurretPos, lockedTarget);
                fireCooldownSeconds += fireInterval;
            }
        }

        // 4. Advance pooled projectiles and resolve hits
        updateProjectiles(deltaSeconds);
    }

    private void updateRaiders(float deltaSeconds, Vector2 convoyPos, Vector2 convoyVel) {
        for (int i = activeRaiders.size - 1; i >= 0; i--) {
            RaiderTarget raider = activeRaiders.get(i);
            if (!raider.active || raider.currentHp <= 0.0f) {
                activeRaiders.removeIndex(i);
                raiderPool.free(raider);
                continue;
            }

            raider.position.mulAdd(raider.velocity, deltaSeconds);

            if (raider.archetype == RaiderArchetype.MORTAR_SKIFF) {
                raider.attackCooldownSec -= deltaSeconds;
                float dist = raider.position.dst(convoyPos);
                if (raider.attackCooldownSec <= 0.0f && dist <= config.getTurretRangeMeters() * 1.15f) {
                    float bleedThrough = reactorController.absorbIncomingDamage(config.getMortarSkiffShellDamage());
                    convoyHullDamageTaken += bleedThrough;
                    raider.attackCooldownSec += config.getMortarSkiffFireIntervalSec();
                }
            }
        }
    }

    /**
     * Selects the highest-priority active raider within turret range.
     * Close-range RAMMER_BUGGY threats receive a distance urgency bonus; MORTAR_SKIFF threats
     * are engaged across medium-to-long range.
     */
    public RaiderTarget selectPriorityTarget(Vector2 turretWorldPos) {
        RaiderTarget best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        float maxRange2 = config.getTurretRangeMeters() * config.getTurretRangeMeters();

        for (int i = 0; i < activeRaiders.size; i++) {
            RaiderTarget candidate = activeRaiders.get(i);
            if (!candidate.active || candidate.currentHp <= 0.0f) {
                continue;
            }
            float dist2 = turretWorldPos.dst2(candidate.position);
            if (dist2 > maxRange2) {
                continue;
            }
            float dist = (float) Math.sqrt(dist2);
            // Closer targets score higher; Rammer Buggies within 45m get immediate intercept priority
            float score = (config.getTurretRangeMeters() - dist);
            if (candidate.archetype == RaiderArchetype.RAMMER_BUGGY && dist < 45.0f) {
                score += 35.0f;
            } else if (candidate.archetype == RaiderArchetype.MORTAR_SKIFF) {
                score += 15.0f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private void fireProjectileAt(Vector2 turretWorldPos, RaiderTarget target) {
        float projSpeed = config.getProjectileSpeedMps();
        float dist = turretWorldPos.dst(target.position);
        float timeToIntercept = dist / Math.max(1.0f, projSpeed);

        scratchLeadPos.set(target.position).mulAdd(target.velocity, timeToIntercept);
        scratchAimVector.set(scratchLeadPos).sub(turretWorldPos);
        if (scratchAimVector.len2() < 1e-6f) {
            scratchAimVector.set(1.0f, 0.0f);
        } else {
            scratchAimVector.nor();
        }

        turretAimAngleRad = MathUtils.atan2(scratchAimVector.y, scratchAimVector.x);

        TurretProjectile proj = projectilePool.obtain();
        proj.position.set(turretWorldPos);
        proj.velocity.set(scratchAimVector).scl(projSpeed);
        proj.targetId = target.id;
        proj.damage = config.getBaseProjectileDamage() * reactorController.getArtilleryDamageMultiplier();
        proj.remainingLifetimeSec = (config.getTurretRangeMeters() / projSpeed) + 0.5f;
        proj.active = true;
        activeProjectiles.add(proj);

        totalShotsFired++;
        reactorController.addThermalLoad(config.getHeatPerArtilleryShot() * 0.25f);
    }

    private void updateProjectiles(float deltaSeconds) {
        float hitRadius2 = 3.5f * 3.5f;
        for (int i = activeProjectiles.size - 1; i >= 0; i--) {
            TurretProjectile proj = activeProjectiles.get(i);
            proj.position.mulAdd(proj.velocity, deltaSeconds);
            proj.remainingLifetimeSec -= deltaSeconds;

            boolean hit = false;
            for (int j = activeRaiders.size - 1; j >= 0; j--) {
                RaiderTarget raider = activeRaiders.get(j);
                if (!raider.active || raider.currentHp <= 0.0f) {
                    continue;
                }
                if (proj.position.dst2(raider.position) <= hitRadius2) {
                    raider.currentHp -= proj.damage;
                    totalHitsLanded++;
                    hit = true;
                    if (raider.currentHp <= 0.0f) {
                        raider.active = false;
                        if (raider.archetype == RaiderArchetype.RAMMER_BUGGY) {
                            rammerBuggiesDestroyed++;
                        } else if (raider.archetype == RaiderArchetype.MORTAR_SKIFF) {
                            mortarSkiffsDestroyed++;
                        }
                        activeRaiders.removeIndex(j);
                        raiderPool.free(raider);
                    }
                    break;
                }
            }

            if (hit || proj.remainingLifetimeSec <= 0.0f) {
                activeProjectiles.removeIndex(i);
                projectilePool.free(proj);
            }
        }
    }

    public Array<RaiderTarget> getActiveRaiders() { return activeRaiders; }
    public Array<TurretProjectile> getActiveProjectiles() { return activeProjectiles; }
    public int getFreeRaiderPoolCount() { return raiderPool.getFree(); }
    public int getFreeProjectilePoolCount() { return projectilePool.getFree(); }

    public float getTurretAimAngleRad() { return turretAimAngleRad; }
    public int getTotalShotsFired() { return totalShotsFired; }
    public int getTotalHitsLanded() { return totalHitsLanded; }
    public int getRammerBuggiesDestroyed() { return rammerBuggiesDestroyed; }
    public int getMortarSkiffsDestroyed() { return mortarSkiffsDestroyed; }
    public int getTotalRaidersDestroyed() { return rammerBuggiesDestroyed + mortarSkiffsDestroyed; }
    public float getConvoyHullDamageTaken() { return convoyHullDamageTaken; }
}
