package com.chronoconvoy.client.replay;

import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.combat.TurretCombatSystem;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * [CLI-02] Deterministic seed + tick action-log recorder and state-hash generator
 * for backend replay verification (/api/v1/run/verify).
 */
public final class DeterministicReplayRecorder {

    public static final String DEFAULT_SIGNING_SECRET = "chrono-convoy-iron-drift-sprint1-master-secret-2026";
    public static final String DEFAULT_KEY_ID = "chrono-key-2026-v1";

    public enum ActionType {
        ALLOCATE_WATTS,
        SWITCH_FORK,
        CONSUME_COOLING_ROD,
        SET_THROTTLE_BRAKE
    }

    public record ReplayAction(
        long tick,
        ActionType type,
        float param1,
        float param2,
        float param3,
        String label
    ) {}

    private final long seed;
    private final String runId;
    private final List<ReplayAction> actions = new ArrayList<>();
    private long rollingStateHash = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis

    public DeterministicReplayRecorder(long seed, String runId) {
        this.seed = seed;
        this.runId = Objects.requireNonNullElse(runId, "run-default");
        mixLong(seed);
    }

    public void recordWattAllocation(long tick, float shieldsWatts, float artilleryWatts, float thrustersWatts) {
        actions.add(new ReplayAction(tick, ActionType.ALLOCATE_WATTS, shieldsWatts, artilleryWatts, thrustersWatts, "WATTS"));
        mixLong(tick);
        mixFloat(shieldsWatts);
        mixFloat(artilleryWatts);
        mixFloat(thrustersWatts);
    }

    public void recordForkSwitch(long tick, ForkDirection direction) {
        actions.add(new ReplayAction(tick, ActionType.SWITCH_FORK, direction.ordinal(), direction.getLateralSign(), 0.0f, direction.name()));
        mixLong(tick);
        mixLong(direction.ordinal());
    }

    public void recordCoolingRodUsed(long tick, float heatAfterVent) {
        actions.add(new ReplayAction(tick, ActionType.CONSUME_COOLING_ROD, heatAfterVent, 0.0f, 0.0f, "VENT_ROD"));
        mixLong(tick);
        mixFloat(heatAfterVent);
    }

    public void recordThrottleAndBrake(long tick, float throttle, float brake) {
        actions.add(new ReplayAction(tick, ActionType.SET_THROTTLE_BRAKE, throttle, brake, 0.0f, "DRIVE"));
        mixLong(tick);
        mixFloat(throttle);
        mixFloat(brake);
    }

    /**
     * Folds a deterministic snapshot of the current tick's simulation state into the rolling state hash.
     */
    public void recordTickState(
        long tick,
        ConvoyPhysicsSystem physics,
        ReactorPowerController reactor,
        TurretCombatSystem combat
    ) {
        mixLong(tick);
        mixFloat(quantize(physics.getDistanceAlongTrackMeters()));
        mixFloat(quantize(physics.getForwardSpeedMps()));
        mixFloat(quantize(physics.getLocomotiveBody().getPosition().x));
        mixFloat(quantize(physics.getLocomotiveBody().getPosition().y));
        mixFloat(quantize(reactor.getShieldsWatts()));
        mixFloat(quantize(reactor.getArtilleryWatts()));
        mixFloat(quantize(reactor.getThrustersWatts()));
        mixFloat(quantize(reactor.getCurrentHeat()));
        mixFloat(quantize(reactor.getCurrentShieldHp()));
        mixLong(combat.getTotalShotsFired());
        mixLong(combat.getTotalHitsLanded());
        mixLong(combat.getTotalRaidersDestroyed());
    }

    private static float quantize(float value) {
        return Math.round(value * 1000.0f) / 1000.0f;
    }

    private void mixLong(long v) {
        for (int i = 0; i < 8; i++) {
            long byteVal = (v >>> (i * 8)) & 0xFFL;
            rollingStateHash ^= byteVal;
            rollingStateHash *= 0x100000001b3L;
        }
    }

    private void mixFloat(float f) {
        mixLong(Float.floatToIntBits(f));
    }

    public long getSeed() {
        return seed;
    }

    public String getRunId() {
        return runId;
    }

    public List<ReplayAction> getActions() {
        return Collections.unmodifiableList(actions);
    }

    public long getRollingStateHash() {
        return rollingStateHash;
    }

    public String computeSha256Hex(long finalTick, float finalDistanceMeters) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String canonical = String.format(
                Locale.ROOT,
                "run=%s|seed=%d|ticks=%d|dist=%.3f|fnv=%016x|actions=%d",
                runId, seed, finalTick, quantize(finalDistanceMeters), rollingStateHash, actions.size()
            );
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Serializes the replay verification payload in canonical JSON format for /api/v1/run/verify.
     */
    public String toVerifyJsonPayload(long finalTick, float finalDistanceMeters, int coolingRodsConsumed) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append(String.format(Locale.ROOT, "  \"run_id\": \"%s\",\n", runId));
        sb.append(String.format(Locale.ROOT, "  \"seed\": %d,\n", seed));
        sb.append(String.format(Locale.ROOT, "  \"total_ticks\": %d,\n", finalTick));
        sb.append(String.format(Locale.ROOT, "  \"distance_meters\": %.3f,\n", quantize(finalDistanceMeters)));
        sb.append(String.format(Locale.ROOT, "  \"cooling_rods_consumed\": %d,\n", coolingRodsConsumed));
        sb.append(String.format(Locale.ROOT, "  \"state_hash_fnv1a\": \"%016x\",\n", rollingStateHash));
        sb.append(String.format(Locale.ROOT, "  \"state_hash_sha256\": \"%s\",\n", computeSha256Hex(finalTick, finalDistanceMeters)));
        sb.append("  \"actions\": [\n");
        for (int i = 0; i < actions.size(); i++) {
            ReplayAction a = actions.get(i);
            sb.append(String.format(
                Locale.ROOT,
                "    {\"tick\": %d, \"type\": \"%s\", \"p1\": %.3f, \"p2\": %.3f, \"p3\": %.3f, \"label\": \"%s\"}",
                a.tick(), a.type().name(), a.param1(), a.param2(), a.param3(), a.label()
            ));
            if (i < actions.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a signed VerifyRunRequest JSON envelope compatible with backend/internal/replay/verifier.go
     * (HMAC-SHA256 signed with KeyID "chrono-key-2026-v1").
     */
    public String toSignedBackendVerifyRequestJson(
        String playerId,
        String convoyId,
        String originHubId,
        String destinationHubId,
        String secretMaterial
    ) {
        StringBuilder actionsJson = new StringBuilder("[");
        boolean first = true;
        for (ReplayAction a : actions) {
            String entry = switch (a.type()) {
                case ALLOCATE_WATTS -> {
                    int s = Math.min(60, Math.round(a.param1()));
                    int art = Math.min(60, Math.round(a.param2()));
                    int thr = Math.min(60, Math.max(0, Math.min(100 - s - art, Math.round(a.param3()))));
                    yield String.format(Locale.ROOT,
                        "{\"tick\":%d,\"type\":\"ALLOCATE_WATTS\",\"shields_watts\":%d,\"artillery_watts\":%d,\"thrusters_watts\":%d}",
                        a.tick(), s, art, thr);
                }
                case SWITCH_FORK -> {
                    String branchId = "LEFT_BRANCH".equals(a.label()) ? "high_threat_scrap_canyon" : "low_yield_salt_flats";
                    yield String.format(Locale.ROOT,
                        "{\"tick\":%d,\"type\":\"SWITCH_BRANCH\",\"branch_id\":\"%s\"}",
                        a.tick(), branchId);
                }
                case CONSUME_COOLING_ROD -> String.format(Locale.ROOT,
                    "{\"tick\":%d,\"type\":\"FLUSH_COOLING_ROD\"}", a.tick());
                default -> null;
            };
            if (entry != null) {
                if (!first) {
                    actionsJson.append(",");
                }
                actionsJson.append(entry);
                first = false;
            }
        }
        if (first) {
            actionsJson.append("{\"tick\":1,\"type\":\"ALLOCATE_WATTS\",\"shields_watts\":30,\"artillery_watts\":35,\"thrusters_watts\":35}");
        }
        actionsJson.append("]");

        String canonicalPayload = String.format(
            Locale.ROOT,
            "v1|%s|%s|%s|%s|%s|%d|%s",
            runId, playerId, convoyId, originHubId, destinationHubId, seed, actionsJson
        );
        String hmacHex = signCanonicalPayloadHmacSha256(canonicalPayload, secretMaterial);

        return String.format(
            Locale.ROOT,
            "{\"run_id\":\"%s\",\"idempotency_key\":\"run_verify_%s\",\"player_id\":\"%s\",\"convoy_id\":\"%s\",\"origin_hub_id\":\"%s\",\"destination_hub_id\":\"%s\",\"seed\":%d,\"max_reactor_watts\":100,\"initial_cooling_rods\":10,\"key_id\":\"%s\",\"signature_hmac\":\"%s\",\"actions\":%s}",
            runId, runId, playerId, convoyId, originHubId, destinationHubId, seed, DEFAULT_KEY_ID, hmacHex, actionsJson
        );
    }

    private static String signCanonicalPayloadHmacSha256(String canonicalPayload, String secretMaterial) {
        try {
            String effectiveSecret = (secretMaterial == null || secretMaterial.isEmpty())
                    ? DEFAULT_SIGNING_SECRET : secretMaterial;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derivedKey = sha256.digest(effectiveSecret.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derivedKey, "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign replay payload", e);
        }
    }
}
