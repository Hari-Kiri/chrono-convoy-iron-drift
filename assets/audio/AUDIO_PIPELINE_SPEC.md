# Chrono-Convoy: Iron Drift — Sprint 1 Audio Pipeline & Adaptive Music Specification (`[AUD-01]`)

- **Ticket ID:** `[AUD-01] Velocity-Synced Industrial Soundtrack Stems & Artillery/Ramming SFX` (`z8rctemh4x`)
- **Owner:** @Echo (Game Audio Designer & Composer)
- **Source Assets Directory:** `/workspace/chrono-convoy-iron-drift/assets/audio/`
- **Runtime Client Directory:** `/workspace/chrono-convoy-iron-drift/client/assets/audio/`
- **Generator Script:** `/workspace/chrono-convoy-iron-drift/assets/audio/generate_sprint1_audio.py`

---

## 1. Vertical Layering Stems (120.0 BPM, 4/4, D-Phrygian Industrial, 4 Bars = 8.000s / 352,800 Samples)

All four vertical layering stems share an identical sample-accurate duration (`352,800` samples at `44,100 Hz`, 16-bit mono PCM WAV) with pre-baked circular reverb/decay wrap-around and zero-crossing boundaries (`sample[0] == 0`, `sample[-1] == 0`) to guarantee click-free looping in LibGDX `Music` instances.

| Stem ID | Filename | Duration | Samples | Peak (dBFS) | RMS (dBFS) | Acoustic Profile & Role |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `stem_01_desert_idle` | `stem_01_desert_idle.wav` | `8.000s` | `352,800` | `-2.50 dBFS` | `-10.83 dBFS` | Periodic D1/A1/D2 sub-drone, filtered desert wind bed, and metallic heat-shimmer harmonics. Dominant at low/stopped velocity (`0–6 m/s`). |
| `stem_02_locomotive_chug` | `stem_02_locomotive_chug.wav` | `8.000s` | `352,800` | `-1.80 dBFS` | `-13.14 dBFS` | 16th-note pneumatic steam/valve chug + D-Phrygian piston sub-bass groove. Crossfades in from `3 m/s` to full cruise (`28 m/s`). |
| `stem_03_raider_percussion` | `stem_03_raider_percussion.wav` | `8.000s` | `352,800` | `-1.50 dBFS` | `-18.54 dBFS` | Siege war-drums, inharmonic modal anvil strikes on backbeats, scrap shaker hats, and bar-4 combat fill. Driven by velocity + `combat_threat_level`. |
| `stem_04_overdrive_synth` | `stem_04_overdrive_synth.wav` | `8.000s` | `352,800` | `-1.50 dBFS` | `-13.95 dBFS` | Overdriven detuned saw/FM arpeggiator + high-tension harmonic stabs. Fades in above `20.9 m/s` or when Reactor Heat crosses `55%–100%` (`WARNING` / `OVERCLOCK`). |

---

## 2. Core Combat & Diegetic Gameplay SFX (`44,100 Hz`, 16-bit PCM WAV, Transient + Body + Tail)

All combat and diegetic SFX are engineered under `150 KB` for low-latency in-memory LibGDX `Sound` voice pooling, with distinct frequency separation and zero-crossing start/end points.

| Cue ID | Filename | Duration | Samples | Peak (dBFS) | RMS (dBFS) | Layered Synthesis Architecture (Transient + Body + Tail) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `sfx_artillery_fire` | `sfx_artillery_fire.wav` | `1.650s` | `72,765` | `-1.00 dBFS` | `-20.08 dBFS` | **Transient:** Propellant ignition crack (`0.9–8.5 kHz`). **Body:** `199 Hz -> 34 Hz` siege mortar boom + steel breech modal ring. **Tail:** Multi-tap desert canyon acoustic reflection (`45–480 Hz`). |
| `sfx_shield_deflect` | `sfx_shield_deflect.wav` | `0.950s` | `41,895` | `-1.20 dBFS` | `-20.72 dBFS` | **Transient:** High-voltage plasma arc snap (`2.2–12 kHz`). **Body:** Dispersive FM harmonic deflection sweep. **Tail:** Kinetic ricochet pitch slide (`2.9 kHz -> 1.25 kHz`) + plasma shimmer. |
| `sfx_heavy_ram_impact` | `sfx_heavy_ram_impact.wav` | `1.450s` | `63,945` | `-0.90 dBFS` | `-17.52 dBFS` | **Transient:** Sub-bass kinetic prow slam (`180 Hz -> 30 Hz`) + broadband steel crunch. **Body:** Inharmonic twisted-steel FM screech + staggered scrap debris clatters. **Tail:** `42 Hz` & `78.5 Hz` hull shudder. |
| `sfx_reactor_alarm` | `sfx_reactor_alarm.wav` | `1.500s` | `66,150` | `-1.50 dBFS` | `-6.24 dBFS` | **3-Pulse Diegetic Klaxon (`0.50s/pulse`):** `880 Hz <-> 1320 Hz` bi-tonal brass horn sweep + thermal relay click transient. Triggered when `reactor_heat >= 75.0`. |
| `sfx_track_switch` | `sfx_track_switch.wav` | `0.850s` | `37,485` | `-1.20 dBFS` | `-18.86 dBFS` | **Transient (`0.00s`):** Heavy iron switch-lever throw clank. **Body (`0.06–0.42s`):** Pneumatic cylinder hiss & sliding rail grind. **Tail (`0.40–0.85s`):** Steel rail-frog lock slam & modal ring. |

---

## 3. Adaptive Audio Configuration (`adaptive_audio_cues.json`)

The runtime configuration file (`assets/audio/adaptive_audio_cues.json` and `client/assets/audio/adaptive_audio_cues.json`) maps real-time telemetry from `docs/design/convoy_config.json` directly to audio parameters:
- **Normalized Velocity (`v_norm`):** `clamp(convoy_velocity_mps / 38.0, 0.0, 1.0)` (aligned with `min_cruise_speed_mps = 6.0`, `base_max_speed_mps = 28.0`, `overclock_max_speed_mps = 38.0`).
- **Normalized Reactor Heat (`heat_norm`):** `clamp(reactor_heat / 100.0, 0.0, 1.0)` (aligned with `thermal_warning_threshold = 75.0` and `thermal_shutdown_threshold = 100.0`).
- **Priority Ducking & Voice Pooling:** Defines `MASTER`, `MUSIC_STEMS`, `COMBAT_SFX` (12 voices max), and `CRITICAL_ALERTS` (4 voices max) buses, with automatic sidechain ducking (`-2.5 dB` to `-5.0 dB`) on `MUSIC_STEMS` when `sfx_heavy_ram_impact`, `sfx_reactor_alarm`, or `sfx_artillery_fire` triggers.
