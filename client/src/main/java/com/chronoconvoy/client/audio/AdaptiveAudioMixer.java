package com.chronoconvoy.client.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.chronoconvoy.client.config.AssetSupport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptive Audio Mixer for Chrono-Convoy: Iron Drift (Sprint 2).
 *
 * Parses @Echo's client/assets/audio/adaptive_audio_cues.json ("chrono-convoy-adaptive-audio-v1"),
 * validates and loads the 4 vertical loop stems and 5 combat SFX WAV files, computes real-time
 * velocity/heat/combat stem crossfade weights & pitch modulation, and enforces SFX cooldowns.
 */
public class AdaptiveAudioMixer implements Disposable {

    public static final String STEM_DESERT_IDLE = "desert_idle";
    public static final String STEM_LOCOMOTIVE_CHUG = "locomotive_chug";
    public static final String STEM_RAIDER_PERCUSSION = "raider_percussion";
    public static final String STEM_OVERDRIVE_SYNTH = "overdrive_synth";

    public static final String SFX_ARTILLERY_FIRE = "artillery_fire";
    public static final String SFX_SHIELD_DEFLECT = "shield_deflect";
    public static final String SFX_HEAVY_RAM_IMPACT = "heavy_ram_impact";
    public static final String SFX_REACTOR_ALARM = "reactor_alarm";
    public static final String SFX_TRACK_SWITCH = "track_switch";

    public static final List<String> REQUIRED_STEM_FILES = List.of(
        "stem_01_desert_idle.wav",
        "stem_02_locomotive_chug.wav",
        "stem_03_raider_percussion.wav",
        "stem_04_overdrive_synth.wav"
    );

    public static final List<String> REQUIRED_SFX_FILES = List.of(
        "sfx_artillery_fire.wav",
        "sfx_shield_deflect.wav",
        "sfx_heavy_ram_impact.wav",
        "sfx_reactor_alarm.wav",
        "sfx_track_switch.wav"
    );

    public static final class StemState {
        private final String id;
        private final String fileName;
        private final float baseGain;
        private final int sampleRateHz;
        private final int bitsPerSample;
        private final int dataSizeBytes;
        private float currentVolume;
        private float targetVolume;
        private float currentPitch = 1.0f;

        public StemState(String id, String fileName, float baseGain, int sampleRateHz, int bitsPerSample, int dataSizeBytes) {
            this.id = id;
            this.fileName = fileName;
            this.baseGain = baseGain;
            this.sampleRateHz = sampleRateHz;
            this.bitsPerSample = bitsPerSample;
            this.dataSizeBytes = dataSizeBytes;
            this.currentVolume = STEM_DESERT_IDLE.equals(id) ? baseGain : 0.0f;
            this.targetVolume = this.currentVolume;
        }

        public String getId() { return id; }
        public String getFileName() { return fileName; }
        public float getBaseGain() { return baseGain; }
        public int getSampleRateHz() { return sampleRateHz; }
        public int getBitsPerSample() { return bitsPerSample; }
        public int getDataSizeBytes() { return dataSizeBytes; }
        public float getCurrentVolume() { return currentVolume; }
        public float getTargetVolume() { return targetVolume; }
        public float getCurrentPitch() { return currentPitch; }
    }

    public static final class SfxCueSpec {
        private final String id;
        private final String fileName;
        private final String bus;
        private final int priority;
        private final long cooldownMs;
        private final float baseGain;
        private final int sampleRateHz;
        private final int dataSizeBytes;
        private long lastTriggeredTimeMs = -100_000L;
        private int triggerCount = 0;

        public SfxCueSpec(
                String id,
                String fileName,
                String bus,
                int priority,
                long cooldownMs,
                float baseGain,
                int sampleRateHz,
                int dataSizeBytes) {
            this.id = id;
            this.fileName = fileName;
            this.bus = bus;
            this.priority = priority;
            this.cooldownMs = cooldownMs;
            this.baseGain = baseGain;
            this.sampleRateHz = sampleRateHz;
            this.dataSizeBytes = dataSizeBytes;
        }

        public String getId() { return id; }
        public String getFileName() { return fileName; }
        public String getBus() { return bus; }
        public int getPriority() { return priority; }
        public long getCooldownMs() { return cooldownMs; }
        public float getBaseGain() { return baseGain; }
        public int getSampleRateHz() { return sampleRateHz; }
        public int getDataSizeBytes() { return dataSizeBytes; }
        public int getTriggerCount() { return triggerCount; }
    }

    private String schemaId = "chrono-convoy-adaptive-audio-v1";
    private int tempoBpm = 120;
    private float loopDurationSeconds = 8.0f;
    private int maxSimultaneousSfxVoices = 16;

    private final Map<String, StemState> stemsById = new LinkedHashMap<>();
    private final Map<String, SfxCueSpec> sfxById = new LinkedHashMap<>();
    private final Map<String, Music> gdxMusicById = new LinkedHashMap<>();
    private final Map<String, Sound> gdxSoundById = new LinkedHashMap<>();

    private long virtualClockMs = 0L;
    private boolean disposed = false;

    public AdaptiveAudioMixer() {
        AssetSupport.ensureHeadlessFilesInitialized();
        loadCuesAndValidateWavs();
    }

    private static String normalizeStemId(String rawId) {
        if (rawId == null) {
            return "";
        }
        return rawId.replaceFirst("^stem_\\d+_", "");
    }

    private static String normalizeSfxId(String rawId) {
        if (rawId == null) {
            return "";
        }
        return rawId.replaceFirst("^sfx_", "");
    }

    private void loadCuesAndValidateWavs() {
        String jsonText;
        try {
            jsonText = AssetSupport.readString("audio/adaptive_audio_cues.json");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read audio/adaptive_audio_cues.json", e);
        }

        JsonValue root = new JsonReader().parse(jsonText);
        if (root.has("schema_id")) {
            schemaId = root.getString("schema_id", schemaId);
        }
        JsonValue mastering = root.get("mastering_standards");
        if (mastering != null) {
            tempoBpm = Math.round(mastering.getFloat("stem_bpm", tempoBpm));
            loopDurationSeconds = mastering.getFloat("loop_duration_sec", loopDurationSeconds);
        }

        JsonValue stemsNode = root.get("vertical_layering_stems");
        if (stemsNode == null) {
            stemsNode = root.get("adaptive_music_stems");
        }
        if (stemsNode != null) {
            for (JsonValue stem = stemsNode.child; stem != null; stem = stem.next) {
                String rawId = stem.has("id") ? stem.getString("id") : stem.name;
                String id = normalizeStemId(rawId);
                String file = stem.getString("file");
                float baseGain = stem.getFloat("base_gain", 0.85f);
                WavHeaderInfo wavInfo = inspectWavFile("audio/" + file);
                StemState state = new StemState(id, file, baseGain, wavInfo.sampleRate, wavInfo.bitsPerSample, wavInfo.dataBytes);
                stemsById.put(id, state);

                if (Gdx.audio != null && Gdx.gl != null) {
                    try {
                        Music music = Gdx.audio.newMusic(AssetSupport.resolveFileHandle("audio/" + file));
                        music.setLooping(true);
                        music.setVolume(state.currentVolume);
                        music.play();
                        gdxMusicById.put(id, music);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        JsonValue sfxNode = root.get("combat_sfx_cues");
        if (sfxNode == null) {
            sfxNode = root.get("sfx_cues");
        }
        if (sfxNode != null) {
            for (JsonValue cue = sfxNode.child; cue != null; cue = cue.next) {
                String rawId = cue.has("id") ? cue.getString("id") : cue.name;
                String id = normalizeSfxId(rawId);
                String file = cue.getString("file");
                String bus = cue.getString("bus", "COMBAT_SFX");
                int priority = cue.getInt("priority", 50);
                long cooldownMs = cue.getLong("cooldown_ms", 150L);
                float baseGain = cue.has("default_volume")
                        ? cue.getFloat("default_volume", 0.85f)
                        : cue.getFloat("base_gain", 0.85f);
                WavHeaderInfo wavInfo = inspectWavFile("audio/" + file);
                SfxCueSpec spec = new SfxCueSpec(id, file, bus, priority, cooldownMs, baseGain, wavInfo.sampleRate, wavInfo.dataBytes);
                sfxById.put(id, spec);

                if (Gdx.audio != null && Gdx.gl != null) {
                    try {
                        Sound sound = Gdx.audio.newSound(AssetSupport.resolveFileHandle("audio/" + file));
                        gdxSoundById.put(id, sound);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static final class WavHeaderInfo {
        final int sampleRate;
        final int bitsPerSample;
        final int dataBytes;

        WavHeaderInfo(int sampleRate, int bitsPerSample, int dataBytes) {
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
            this.dataBytes = dataBytes;
        }
    }

    private static WavHeaderInfo inspectWavFile(String relativeAssetPath) {
        try {
            byte[] bytes = AssetSupport.readBytes(relativeAssetPath);
            if (bytes.length < 44) {
                throw new IllegalStateException("WAV file too small: " + relativeAssetPath);
            }
            String riff = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
            String wave = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
            if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
                throw new IllegalStateException("Invalid RIFF/WAVE header in " + relativeAssetPath);
            }
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int sampleRate = buf.getInt(24);
            int bitsPerSample = Short.toUnsignedInt(buf.getShort(34));
            int dataBytes = Math.max(0, bytes.length - 44);
            return new WavHeaderInfo(sampleRate, bitsPerSample, dataBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Missing audio WAV asset: " + relativeAssetPath, e);
        }
    }

    /**
     * Updates vertical stem crossfade weights and locomotive chug pitch based on real-time
     * convoy velocity, reactor core heat percentage, combat intensity, and overdrive state.
     */
    public void update(
            float speedMps,
            float reactorHeatPct,
            float combatThreatLevel,
            boolean overdriveActive,
            float deltaSeconds) {
        if (deltaSeconds > 0.0f) {
            virtualClockMs += Math.max(1L, Math.round(deltaSeconds * 1000.0f));
        }

        float clampedSpeed = Math.max(0.0f, speedMps);
        float clampedHeat = Math.max(0.0f, Math.min(100.0f, reactorHeatPct));
        float clampedThreat = Math.max(0.0f, Math.min(1.0f, combatThreatLevel));

        // 1. Stem 01: Desert Idle (base ambient, ducks -2.5dB (~0.75x) above 18 m/s)
        StemState idle = stemsById.get(STEM_DESERT_IDLE);
        if (idle != null) {
            float speedDuckFactor = clampedSpeed > 18.0f ? 0.75f : 1.0f;
            idle.targetVolume = idle.baseGain * speedDuckFactor;
            idle.currentVolume = approach(idle.currentVolume, idle.targetVolume, deltaSeconds * 2.5f);
        }

        // 2. Stem 02: Locomotive Chug (scales with velocity 0.5..25.0 m/s, pitch 0.82..1.28)
        StemState chug = stemsById.get(STEM_LOCOMOTIVE_CHUG);
        if (chug != null) {
            float velNorm = clamp01((clampedSpeed - 0.5f) / 24.5f);
            chug.targetVolume = chug.baseGain * velNorm;
            chug.currentVolume = approach(chug.currentVolume, chug.targetVolume, deltaSeconds * 3.0f);
            float pitchNorm = clamp01(clampedSpeed / 35.0f);
            chug.currentPitch = 0.82f + (1.28f - 0.82f) * pitchNorm;
        }

        // 3. Stem 03: Raider Percussion (scales with combatThreatLevel 0.10..0.85)
        StemState percussion = stemsById.get(STEM_RAIDER_PERCUSSION);
        if (percussion != null) {
            float threatNorm = clamp01((clampedThreat - 0.10f) / 0.75f);
            percussion.targetVolume = percussion.baseGain * threatNorm;
            percussion.currentVolume = approach(percussion.currentVolume, percussion.targetVolume, deltaSeconds * 2.8f);
        }

        // 4. Stem 04: Overdrive Synth (scales with reactorHeatPct >= 65% or overdriveActive)
        StemState synth = stemsById.get(STEM_OVERDRIVE_SYNTH);
        if (synth != null) {
            float heatNorm = clamp01((clampedHeat - 65.0f) / 30.0f);
            if (overdriveActive) {
                heatNorm = Math.max(heatNorm, 0.90f);
            }
            synth.targetVolume = synth.baseGain * heatNorm;
            synth.currentVolume = approach(synth.currentVolume, synth.targetVolume, deltaSeconds * 4.0f);
        }

        // Automatically trigger reactor alarm when core heat >= 75%
        if (clampedHeat >= 75.0f) {
            triggerReactorAlarm();
        }

        // Sync LibGDX Music volumes if running with live audio
        for (Map.Entry<String, Music> entry : gdxMusicById.entrySet()) {
            StemState st = stemsById.get(entry.getKey());
            if (st != null) {
                try {
                    entry.getValue().setVolume(st.currentVolume);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean triggerSfx(String cueId) {
        String cleanId = normalizeSfxId(cueId);
        SfxCueSpec cue = sfxById.get(cleanId);
        if (cue == null) {
            return false;
        }
        if (virtualClockMs - cue.lastTriggeredTimeMs < cue.cooldownMs) {
            return false;
        }
        cue.lastTriggeredTimeMs = virtualClockMs;
        cue.triggerCount++;

        Sound sound = gdxSoundById.get(cleanId);
        if (sound != null) {
            try {
                sound.play(cue.baseGain);
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    public boolean triggerArtilleryFire() { return triggerSfx(SFX_ARTILLERY_FIRE); }
    public boolean triggerShieldDeflect() { return triggerSfx(SFX_SHIELD_DEFLECT); }
    public boolean triggerHeavyRamImpact() { return triggerSfx(SFX_HEAVY_RAM_IMPACT); }
    public boolean triggerReactorAlarm() { return triggerSfx(SFX_REACTOR_ALARM); }
    public boolean triggerTrackSwitch() { return triggerSfx(SFX_TRACK_SWITCH); }

    public void advanceClockMs(long deltaMs) {
        if (deltaMs > 0) {
            this.virtualClockMs += deltaMs;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static float approach(float current, float target, float maxDelta) {
        if (maxDelta <= 0.0f) {
            return target;
        }
        if (Math.abs(target - current) <= maxDelta) {
            return target;
        }
        return current + Math.copySign(maxDelta, target - current);
    }

    public String getSchemaId() { return schemaId; }
    public int getTempoBpm() { return tempoBpm; }
    public float getLoopDurationSeconds() { return loopDurationSeconds; }
    public int getMaxSimultaneousSfxVoices() { return maxSimultaneousSfxVoices; }

    public Map<String, StemState> getStems() { return Collections.unmodifiableMap(stemsById); }
    public StemState getStem(String stemId) { return stemsById.get(normalizeStemId(stemId)); }
    public float getStemVolume(String stemId) {
        StemState s = stemsById.get(normalizeStemId(stemId));
        return s != null ? s.currentVolume : 0.0f;
    }

    public Map<String, SfxCueSpec> getSfxCues() { return Collections.unmodifiableMap(sfxById); }
    public SfxCueSpec getSfxCue(String cueId) { return sfxById.get(normalizeSfxId(cueId)); }
    public int getTotalSfxTriggers() {
        int total = 0;
        for (SfxCueSpec s : sfxById.values()) {
            total += s.triggerCount;
        }
        return total;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (Music m : gdxMusicById.values()) {
            try {
                m.dispose();
            } catch (Exception ignored) {
            }
        }
        gdxMusicById.clear();
        for (Sound s : gdxSoundById.values()) {
            try {
                s.dispose();
            } catch (Exception ignored) {
            }
        }
        gdxSoundById.clear();
    }
}
