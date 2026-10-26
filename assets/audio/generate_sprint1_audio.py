#!/usr/bin/env python3
"""
Chrono-Convoy: Iron Drift — Sprint 1 Procedural Audio Synthesizer & Exporter
Ticket: [AUD-01] Velocity-Synced Industrial Soundtrack Stems & Artillery/Ramming SFX (z8rctemh4x)
Author: Echo (Game Audio Designer & Composer)

Generates:
1. 4 Loop-Accurate Vertical Layering Stems (44,100 Hz, 16-bit PCM WAV, 120 BPM, 4 bars = 8.000s / 352,800 samples):
   - stem_01_desert_idle.wav
   - stem_02_locomotive_chug.wav
   - stem_03_raider_percussion.wav
   - stem_04_overdrive_synth.wav
2. 5 Core Combat & Gameplay SFX (44,100 Hz, 16-bit PCM WAV, transient + body + tail layered):
   - sfx_artillery_fire.wav
   - sfx_shield_deflect.wav
   - sfx_heavy_ram_impact.wav
   - sfx_reactor_alarm.wav
   - sfx_track_switch.wav
3. Adaptive Audio Configuration & Manifest:
   - adaptive_audio_cues.json
"""

import json
import math
import os
import shutil
import wave
import numpy as np

SAMPLE_RATE = 44100
BPM = 120.0
BEATS_PER_BAR = 4
BARS = 4
LOOP_DURATION_SEC = (60.0 / BPM) * BEATS_PER_BAR * BARS  # 8.0 seconds exact
LOOP_SAMPLES = int(SAMPLE_RATE * LOOP_DURATION_SEC)      # 352,800 samples exact


def set_seed(seed=42069):
    np.random.seed(seed)


def one_pole_lowpass(signal, cutoff_hz, sr=SAMPLE_RATE):
    """Simple one-pole IIR lowpass filter."""
    if cutoff_hz >= sr / 2.0:
        return signal.copy()
    rc = 1.0 / (2.0 * math.pi * max(cutoff_hz, 1.0))
    dt = 1.0 / sr
    alpha = dt / (rc + dt)
    # Vectorized via FFT for circular / fast filtering or recursive loop
    out = np.zeros_like(signal)
    val = 0.0
    for i in range(len(signal)):
        val = val + alpha * (signal[i] - val)
        out[i] = val
    return out


def fft_bandpass(signal, low_hz, high_hz, sr=SAMPLE_RATE):
    """Zero-phase circular FFT bandpass filter (perfect for seamless loops & clean SFX)."""
    n = len(signal)
    freqs = np.fft.rfftfreq(n, d=1.0 / sr)
    spec = np.fft.rfft(signal)
    # Smooth Butterworth-like mask to avoid ringing
    low_mask = 1.0 / (1.0 + ( max(low_hz, 1.0) / np.maximum(freqs, 1e-6) ) ** 6) if low_hz > 0 else np.ones_like(freqs)
    high_mask = 1.0 / (1.0 + ( freqs / max(high_hz, 1.0) ) ** 6) if high_hz < sr / 2 else np.ones_like(freqs)
    filtered = np.fft.irfft(spec * low_mask * high_mask, n=n)
    return filtered


def enforce_seamless_loop_zero_crossing(signal, crossfade_ms=15.0, zero_window_ms=3.5, sr=SAMPLE_RATE):
    """
    Guarantees a 100% click-free, zero-crossing loop boundary:
    1. Removes DC offset.
    2. Applies a half-cosine micro-taper at the first and last `zero_window_ms` so sample[0] == 0.0
       and sample[-1] == 0.0 with zero derivative discontinuity, while preserving pre-baked circular
       harmonics and rhythmic continuity.
    """
    sig = signal - np.mean(signal)
    win_samples = int((zero_window_ms / 1000.0) * sr)
    if win_samples > 1:
        fade_in = 0.5 * (1.0 - np.cos(np.pi * np.arange(win_samples) / win_samples))
        fade_out = 0.5 * (1.0 + np.cos(np.pi * np.arange(win_samples) / win_samples))
        sig[:win_samples] *= fade_in
        sig[-win_samples:] *= fade_out
    sig[0] = 0.0
    sig[-1] = 0.0
    return sig


def normalize_to_dbfs(signal, target_dbfs=-1.5):
    """Normalizes peak amplitude to target_dbfs without clipping."""
    peak = np.max(np.abs(signal))
    if peak < 1e-9:
        return signal
    target_amp = 10.0 ** (target_dbfs / 20.0)
    return signal * (target_amp / peak)


def write_wav_16bit(filepath, signal, sr=SAMPLE_RATE):
    """Writes mono 16-bit PCM WAV file and returns audio diagnostics."""
    clipped = np.clip(signal, -0.9999, 0.9999)
    pcm16 = (clipped * 32767.0).astype(np.int16)
    pcm16[0] = 0
    if len(pcm16) > 1:
        pcm16[-1] = 0
    with wave.open(filepath, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm16.tobytes())

    float_back = pcm16.astype(np.float64) / 32768.0
    peak_amp = float(np.max(np.abs(float_back)))
    rms_amp = float(np.sqrt(np.mean(float_back ** 2)))
    peak_db = 20.0 * math.log10(max(peak_amp, 1e-9))
    rms_db = 20.0 * math.log10(max(rms_amp, 1e-9))
    return {
        "file": os.path.basename(filepath),
        "samples": int(len(pcm16)),
        "duration_sec": round(len(pcm16) / sr, 4),
        "sample_rate_hz": sr,
        "bit_depth": 16,
        "channels": 1,
        "peak_dbfs": round(peak_db, 2),
        "rms_dbfs": round(rms_db, 2),
        "start_sample_int16": int(pcm16[0]),
        "end_sample_int16": int(pcm16[-1]),
        "size_bytes": os.path.getsize(filepath),
    }


def add_circular_event(buffer, start_idx, event_wave):
    """Adds an audio event into a loop buffer with circular wrap-around (pre-baked reverb tail)."""
    n = len(buffer)
    m = len(event_wave)
    indices = (start_idx + np.arange(m)) % n
    np.add.at(buffer, indices, event_wave)


# ==============================================================================
# 1. VERTICAL LAYERING STEMS (8.000s, 120 BPM, D Minor / Phrygian Industrial)
# ==============================================================================

def synth_stem_01_desert_idle():
    """
    stem_01_desert_idle.wav:
    Atmospheric desert wind pad, low sub-drone (D1=36.71Hz, A1=55.0Hz, D2=73.42Hz),
    and subtle metallic heat shimmer. Perfectly periodic over 8.0s (352,800 samples).
    """
    set_seed(101)
    t = np.arange(LOOP_SAMPLES, dtype=np.float64) / SAMPLE_RATE
    # Use integer cycles over 8.0s so every oscillator is 100% phase-continuous across the loop seam!
    # D1 (~36.75 Hz -> 294 cycles in 8s), A1 (~55.0 Hz -> 440 cycles in 8s), D2 (~73.5 Hz -> 588 cycles in 8s)
    f_d1 = 294.0 / LOOP_DURATION_SEC   # 36.75 Hz
    f_a1 = 440.0 / LOOP_DURATION_SEC   # 55.00 Hz
    f_d2 = 588.0 / LOOP_DURATION_SEC   # 73.50 Hz
    f_f2 = 700.0 / LOOP_DURATION_SEC   # 87.50 Hz
    f_a2 = 880.0 / LOOP_DURATION_SEC   # 110.00 Hz

    lfo_slow = np.sin(2.0 * np.pi * (1.0 / LOOP_DURATION_SEC) * t)
    lfo_med = np.sin(2.0 * np.pi * (2.0 / LOOP_DURATION_SEC) * t + 0.6)
    lfo_shimmer = np.sin(2.0 * np.pi * (4.0 / LOOP_DURATION_SEC) * t + 1.2)

    # Warm sub-drone + slow FM breath
    sub_drone = (
        0.48 * np.sin(2.0 * np.pi * f_d1 * t + 0.15 * lfo_slow)
        + 0.28 * np.sin(2.0 * np.pi * f_a1 * t + 0.25 * lfo_med)
        + 0.22 * np.sin(2.0 * np.pi * f_d2 * t + 0.35 * np.sin(2.0 * np.pi * f_d1 * t))
    )

    # Metallic desert heat shimmer (high harmonic resonant bells slowly undulating)
    shimmer = (
        0.10 * np.sin(2.0 * np.pi * f_f2 * 4.0 * t + 0.8 * lfo_shimmer) * (0.5 + 0.5 * lfo_med)
        + 0.08 * np.sin(2.0 * np.pi * f_a2 * 5.0 * t - 0.6 * lfo_slow) * (0.5 + 0.5 * lfo_shimmer)
        + 0.05 * np.sin(2.0 * np.pi * f_d2 * 8.0 * t + 1.1 * lfo_med)
    )

    # Periodic desert wind noise (filtered white noise shaped in frequency domain so it wraps seamlessly)
    raw_noise = np.random.randn(LOOP_SAMPLES)
    wind_low = fft_bandpass(raw_noise, 140.0, 620.0)
    wind_high = fft_bandpass(raw_noise, 650.0, 2200.0)
    wind_layer = (
        0.18 * wind_low * (0.65 + 0.35 * lfo_slow)
        + 0.09 * wind_high * (0.55 + 0.45 * lfo_med)
    )

    mix = sub_drone + shimmer + wind_layer
    mix = enforce_seamless_loop_zero_crossing(mix)
    return normalize_to_dbfs(mix, target_dbfs=-2.5)


def synth_stem_02_locomotive_chug():
    """
    stem_02_locomotive_chug.wav:
    Rhythmic chugging piston/steam chug (16th-note chug groove at 120 BPM = 8 Hz / 0.125s per 16th)
    and industrial bass groove synchronized to 8.0s loop.
    """
    set_seed(202)
    buf = np.zeros(LOOP_SAMPLES, dtype=np.float64)
    sixteenth_sec = (60.0 / BPM) / 4.0  # 0.125s
    total_sixteenths = int(BARS * BEATS_PER_BAR * 4)  # 64 sixteenths

    # Industrial bass pattern in D Phrygian (D1, D1, F1, D1, Eb1, D1, C1, A0)
    bass_freqs = [
        36.71, 36.71, 36.71, 43.65,  36.71, 36.71, 38.89, 32.70,
        36.71, 36.71, 43.65, 55.00,  38.89, 36.71, 32.70, 27.50,
    ]

    for step in range(total_sixteenths):
        start_idx = int(step * sixteenth_sec * SAMPLE_RATE)
        in_beat = step % 4
        # Accent pattern: strong on downbeat (0) and offbeat (2), chug ghost notes on (1, 3)
        accent = [1.0, 0.52, 0.78, 0.62][in_beat]

        # 1. Steam / Piston Chug (filtered noise burst + mechanical valve click)
        chug_dur = int(0.115 * SAMPLE_RATE)
        tc = np.arange(chug_dur, dtype=np.float64) / SAMPLE_RATE
        env_chug = np.exp(-tc / (0.042 if in_beat in (0, 2) else 0.028)) * (1.0 - np.exp(-tc / 0.003))
        noise = np.random.randn(chug_dur)
        band_noise = fft_bandpass(noise, 180.0, 3200.0 if in_beat == 0 else 2200.0)
        valve_click = np.sin(2.0 * np.pi * (420.0 * np.exp(-tc * 35.0)) * tc) * np.exp(-tc / 0.012)
        chug_event = accent * (0.45 * band_noise * env_chug + 0.30 * valve_click)
        add_circular_event(buf, start_idx, chug_event)

        # 2. Heavy Piston Sub-Thump & Industrial Bass Groove on 8th notes (even steps)
        if step % 2 == 0:
            note_idx = (step // 2) % len(bass_freqs)
            f0 = bass_freqs[note_idx]
            bass_dur = int(0.24 * SAMPLE_RATE)
            tb = np.arange(bass_dur, dtype=np.float64) / SAMPLE_RATE
            env_bass = np.exp(-tb / 0.14) * (1.0 - np.exp(-tb / 0.004))
            # Pitch drop for piston impact + gritty FM bass body
            pitch_drop = f0 * (1.0 + 1.2 * np.exp(-tb * 45.0))
            phase = 2.0 * np.pi * np.cumsum(pitch_drop) / SAMPLE_RATE
            fm_mod = np.sin(2.0 * np.pi * (f0 * 2.0) * tb) * np.exp(-tb / 0.08)
            bass_wave = (0.65 * np.sin(phase + 1.4 * fm_mod) + 0.35 * np.tanh(2.2 * np.sin(phase))) * env_bass
            add_circular_event(buf, start_idx, 0.85 * bass_wave)

        # 3. High-pressure steam release on beat 4 of bars 2 and 4
        if step in (28, 60):
            steam_dur = int(0.45 * SAMPLE_RATE)
            ts = np.arange(steam_dur, dtype=np.float64) / SAMPLE_RATE
            steam_env = np.exp(-ts / 0.18) * (1.0 - np.exp(-ts / 0.015))
            steam_noise = fft_bandpass(np.random.randn(steam_dur), 1200.0, 6500.0)
            add_circular_event(buf, start_idx, 0.32 * steam_noise * steam_env)

    buf = enforce_seamless_loop_zero_crossing(buf)
    return normalize_to_dbfs(buf, target_dbfs=-1.8)


def synth_stem_03_raider_percussion():
    """
    stem_03_raider_percussion.wav:
    Driving industrial war-drums, anvil strikes, and syncopated metallic percussion
    synchronized to the exact 8.0s loop (120 BPM).
    """
    set_seed(303)
    buf = np.zeros(LOOP_SAMPLES, dtype=np.float64)
    sixteenth_sec = (60.0 / BPM) / 4.0  # 0.125s
    total_sixteenths = 64

    # 16-step rhythmic grid repeated over 4 bars with a war-fill on bar 4
    kick_steps = {0, 3, 6, 8, 11, 14}
    snare_anvil_steps = {4, 12}
    tom_steps = {2, 7, 10, 15}

    for step in range(total_sixteenths):
        start_idx = int(step * sixteenth_sec * SAMPLE_RATE)
        bar_step = step % 16
        is_fill_bar = (step >= 48)

        # Industrial Siege Kick
        if bar_step in kick_steps or (is_fill_bar and bar_step in {12, 14, 15}):
            dur = int(0.32 * SAMPLE_RATE)
            t = np.arange(dur, dtype=np.float64) / SAMPLE_RATE
            f_inst = 46.0 + 135.0 * np.exp(-t * 42.0)
            phase = 2.0 * np.pi * np.cumsum(f_inst) / SAMPLE_RATE
            sub_body = np.tanh(1.8 * np.sin(phase)) * np.exp(-t / 0.13)
            click = fft_bandpass(np.random.randn(dur), 800.0, 4200.0) * np.exp(-t / 0.012)
            kick = 0.85 * sub_body + 0.28 * click
            add_circular_event(buf, start_idx, kick)

        # Metallic Anvil Strike & Industrial Snare on Backbeats (beats 2 & 4)
        if bar_step in snare_anvil_steps or (is_fill_bar and bar_step in {10, 13}):
            dur = int(0.38 * SAMPLE_RATE)
            t = np.arange(dur, dtype=np.float64) / SAMPLE_RATE
            # Inharmonic anvil modal frequencies
            modes = [587.3, 932.3, 1480.0, 2150.0, 3120.0]
            anvil = np.zeros(dur)
            for idx_m, fm in enumerate(modes):
                anvil += (0.5 ** idx_m) * np.sin(2.0 * np.pi * fm * t) * np.exp(-t / (0.09 / (idx_m + 1)))
            snare_body = np.sin(2.0 * np.pi * (185.0 + 90.0 * np.exp(-t * 30.0)) * t) * np.exp(-t / 0.065)
            snare_wires = fft_bandpass(np.random.randn(dur), 1100.0, 7500.0) * np.exp(-t / 0.11)
            hit = 0.45 * anvil + 0.45 * snare_body + 0.42 * snare_wires
            add_circular_event(buf, start_idx, hit)

        # Syncopated War-Toms (Floor/Scrap Drum)
        if bar_step in tom_steps or (is_fill_bar and bar_step in {8, 9, 11, 12, 13, 14, 15}):
            dur = int(0.24 * SAMPLE_RATE)
            t = np.arange(dur, dtype=np.float64) / SAMPLE_RATE
            tom_f0 = 82.0 if (bar_step % 2 == 0) else 108.0
            f_inst = tom_f0 * (1.0 + 0.65 * np.exp(-t * 28.0))
            phase = 2.0 * np.pi * np.cumsum(f_inst) / SAMPLE_RATE
            tom = 0.55 * np.tanh(1.5 * np.sin(phase)) * np.exp(-t / 0.095)
            add_circular_event(buf, start_idx, tom)

        # 16th-note Metallic Scrap Shaker / Chain Hat
        hat_dur = int(0.065 * SAMPLE_RATE)
        th = np.arange(hat_dur, dtype=np.float64) / SAMPLE_RATE
        hat_gain = 0.26 if (bar_step % 2 == 0) else 0.15
        hat_noise = fft_bandpass(np.random.randn(hat_dur), 3800.0, 11000.0) * np.exp(-th / 0.022)
        add_circular_event(buf, start_idx, hat_gain * hat_noise)

    buf = enforce_seamless_loop_zero_crossing(buf)
    return normalize_to_dbfs(buf, target_dbfs=-1.5)


def synth_stem_04_overdrive_synth():
    """
    stem_04_overdrive_synth.wav:
    Aggressive distorted arp/lead synth and high-tension harmonic layer synchronized
    to the exact 8.0s loop (120 BPM).
    """
    set_seed(404)
    buf = np.zeros(LOOP_SAMPLES, dtype=np.float64)
    sixteenth_sec = (60.0 / BPM) / 4.0
    total_sixteenths = 64

    # High-tension D Phrygian Dominant / Industrial Overdrive Arpeggio (Hz)
    # D3=146.83, Eb3=155.56, F#3=185.00, G3=196.00, A3=220.00, Bb3=233.08, C4=261.63, D4=293.66
    arp_notes = [
        146.83, 220.00, 293.66, 220.00,  155.56, 233.08, 311.13, 233.08,
        185.00, 261.63, 370.00, 293.66,  196.00, 233.08, 220.00, 155.56,
    ]

    for step in range(total_sixteenths):
        start_idx = int(step * sixteenth_sec * SAMPLE_RATE)
        f_note = arp_notes[step % len(arp_notes)]
        if step >= 48:
            # Raise octave intensity in bar 4
            f_note *= 1.5 if (step % 4 == 2) else 1.0

        dur = int(0.16 * SAMPLE_RATE)
        t = np.arange(dur, dtype=np.float64) / SAMPLE_RATE
        env = np.exp(-t / 0.075) * (1.0 - np.exp(-t / 0.003))

        # Detuned saw + pulse + FM grit
        saw1 = 2.0 * ((t * f_note) % 1.0) - 1.0
        saw2 = 2.0 * ((t * f_note * 1.006) % 1.0) - 1.0
        sub_sq = np.sign(np.sin(2.0 * np.pi * (f_note * 0.5) * t))
        fm_grit = np.sin(2.0 * np.pi * f_note * t + 1.8 * np.sin(2.0 * np.pi * f_note * 2.0 * t) * np.exp(-t / 0.04))

        raw_arp = (0.38 * saw1 + 0.38 * saw2 + 0.25 * sub_sq + 0.45 * fm_grit) * env
        overdriven_arp = np.tanh(2.4 * raw_arp)
        add_circular_event(buf, start_idx, 0.58 * overdriven_arp)

    # Sustained High-Tension Harmonic Stabs on Bar Boundaries (integer cycles over 8s)
    t_full = np.arange(LOOP_SAMPLES, dtype=np.float64) / SAMPLE_RATE
    f_d3 = 1176.0 / LOOP_DURATION_SEC   # 147.0 Hz
    f_a3 = 1760.0 / LOOP_DURATION_SEC   # 220.0 Hz
    f_eb4 = 2488.0 / LOOP_DURATION_SEC  # 311.0 Hz
    lfo_gate = 0.5 + 0.5 * np.sin(2.0 * np.pi * (8.0 / LOOP_DURATION_SEC) * t_full)
    tension_pad = np.tanh(
        1.8 * (
            0.25 * np.sin(2.0 * np.pi * f_d3 * t_full)
            + 0.22 * np.sin(2.0 * np.pi * f_a3 * t_full)
            + 0.18 * np.sin(2.0 * np.pi * f_eb4 * t_full)
        )
    ) * (0.30 * lfo_gate)

    mix = buf + tension_pad
    mix = enforce_seamless_loop_zero_crossing(mix)
    return normalize_to_dbfs(mix, target_dbfs=-1.5)


# ==============================================================================
# 2. CORE COMBAT & GAMEPLAY SFX (Transient + Body + Tail Layered)
# ==============================================================================

def shape_sfx_edges(signal, fade_in_ms=1.5, fade_out_ms=20.0, sr=SAMPLE_RATE):
    """Applies clean fade-in/out and removes DC offset so SFX start and end at exact 0."""
    sig = signal - np.mean(signal)
    fi = int((fade_in_ms / 1000.0) * sr)
    fo = int((fade_out_ms / 1000.0) * sr)
    if fi > 1:
        sig[:fi] *= np.linspace(0.0, 1.0, fi)
    if fo > 1:
        sig[-fo:] *= np.linspace(1.0, 0.0, fo)
    sig[0] = 0.0
    sig[-1] = 0.0
    return sig


def synth_sfx_artillery_fire():
    """
    sfx_artillery_fire.wav (~1.65s):
    155mm Howitzer Siege Mortar fire:
    - Transient: Punchy propellant ignition crack (0-25ms)
    - Body: Massive low-end cannon boom (190Hz -> 34Hz pitch drop) + steel breech ring
    - Tail: Rolling desert canyon acoustic reflection & dust rumble
    """
    set_seed(501)
    dur_sec = 1.65
    n = int(dur_sec * SAMPLE_RATE)
    t = np.arange(n, dtype=np.float64) / SAMPLE_RATE

    # 1. Transient crack
    crack_noise = fft_bandpass(np.random.randn(n), 900.0, 8500.0)
    transient = 0.95 * crack_noise * np.exp(-t / 0.016)

    # 2. Body: Low-end siege mortar boom + metallic breech ring
    f_boom = 34.0 + 165.0 * np.exp(-t * 28.0)
    phase_boom = 2.0 * np.pi * np.cumsum(f_boom) / SAMPLE_RATE
    boom_body = np.tanh(2.5 * np.sin(phase_boom)) * np.exp(-t / 0.24) * (1.0 - np.exp(-t / 0.002))

    breech_ring = (
        0.28 * np.sin(2.0 * np.pi * 680.0 * t) * np.exp(-t / 0.085)
        + 0.22 * np.sin(2.0 * np.pi * 1140.0 * t) * np.exp(-t / 0.065)
        + 0.15 * np.sin(2.0 * np.pi * 1820.0 * t) * np.exp(-t / 0.045)
    )

    # 3. Tail: Desert canyon echo & low rumble
    rumble_noise = fft_bandpass(np.random.randn(n), 45.0, 480.0)
    echo_env = (
        0.55 * np.exp(-t / 0.42)
        + 0.28 * np.exp(-np.maximum(t - 0.18, 0.0) / 0.35) * (t >= 0.18)
        + 0.16 * np.exp(-np.maximum(t - 0.38, 0.0) / 0.40) * (t >= 0.38)
    )
    tail = rumble_noise * echo_env

    mix = transient + 0.90 * boom_body + breech_ring + 0.65 * tail
    mix = shape_sfx_edges(mix, fade_in_ms=1.0, fade_out_ms=35.0)
    return normalize_to_dbfs(mix, target_dbfs=-1.0)


def synth_sfx_shield_deflect():
    """
    sfx_shield_deflect.wav (~0.95s):
    Aether-Projector Dome Shield deflection:
    - Transient: High-voltage plasma arc snap (0-18ms)
    - Body: Resonant FM harmonic deflection sweep (upward/downward dispersive chirp)
    - Tail: Metallic kinetic ricochet ping & shimmering plasma decay
    """
    set_seed(502)
    dur_sec = 0.95
    n = int(dur_sec * SAMPLE_RATE)
    t = np.arange(n, dtype=np.float64) / SAMPLE_RATE

    # 1. Transient: Energy arc snap
    arc_noise = fft_bandpass(np.random.randn(n), 2200.0, 12000.0)
    transient = 0.75 * arc_noise * np.exp(-t / 0.015)

    # 2. Body: Resonant harmonic deflection sweep
    f_sweep = 320.0 + 1450.0 * np.exp(-t * 11.0) + 180.0 * np.sin(2.0 * np.pi * 18.0 * t)
    phase_sweep = 2.0 * np.pi * np.cumsum(f_sweep) / SAMPLE_RATE
    mod_wave = np.sin(2.0 * np.pi * 640.0 * t) * np.exp(-t / 0.18)
    body = 0.80 * np.sin(phase_sweep + 2.2 * mod_wave) * np.exp(-t / 0.22)

    # 3. Tail: Metallic ricochet ping (high pitch slide) + plasma shimmer
    f_rico = 2900.0 - 1650.0 * (1.0 - np.exp(-t * 7.5))
    phase_rico = 2.0 * np.pi * np.cumsum(f_rico) / SAMPLE_RATE
    ricochet = (
        0.42 * np.sin(phase_rico) * np.exp(-t / 0.28)
        + 0.25 * np.sin(2.0 * np.pi * 4180.0 * t) * np.exp(-t / 0.34)
    )

    mix = transient + body + ricochet
    mix = shape_sfx_edges(mix, fade_in_ms=1.0, fade_out_ms=25.0)
    return normalize_to_dbfs(mix, target_dbfs=-1.2)


def synth_sfx_heavy_ram_impact():
    """
    sfx_heavy_ram_impact.wav (~1.45s):
    Armored Prow vs. Raider Collision:
    - Transient: Brutal steel-on-steel kinetic crunch & sub-bass slam
    - Body: Twisted metal screech (inharmonic FM scrape) + flying scrap debris bursts
    - Tail: Deep resonant locomotive hull shudder (42 Hz & 78 Hz structural modes)
    """
    set_seed(503)
    dur_sec = 1.45
    n = int(dur_sec * SAMPLE_RATE)
    t = np.arange(n, dtype=np.float64) / SAMPLE_RATE

    # 1. Transient: Sub-bass kinetic slam + broadband crunch
    f_slam = 30.0 + 150.0 * np.exp(-t * 36.0)
    phase_slam = 2.0 * np.pi * np.cumsum(f_slam) / SAMPLE_RATE
    sub_slam = np.tanh(3.0 * np.sin(phase_slam)) * np.exp(-t / 0.22)
    crunch_Burst = fft_bandpass(np.random.randn(n), 400.0, 6800.0) * np.exp(-t / 0.038)

    # 2. Body: Twisted steel screech & secondary debris impacts
    f_screech = 480.0 - 190.0 * t
    mod_screech = 1120.0 * np.sin(2.0 * np.pi * 315.0 * t)
    screech = 0.48 * np.tanh(
        2.0 * np.sin(2.0 * np.pi * np.cumsum(f_screech + mod_screech) / SAMPLE_RATE)
    ) * np.exp(-t / 0.28) * (1.0 - np.exp(-t / 0.01))

    # Secondary scrap debris clatters at 75ms, 160ms, 270ms
    debris = np.zeros(n)
    for delay_s, amp, f_metal in [(0.075, 0.40, 1340.0), (0.160, 0.32, 1890.0), (0.270, 0.24, 960.0)]:
        mask = (t >= delay_s).astype(np.float64)
        td = np.maximum(t - delay_s, 0.0)
        debris += mask * amp * (
            np.sin(2.0 * np.pi * f_metal * td) + 0.6 * np.sin(2.0 * np.pi * (f_metal * 1.47) * td)
        ) * np.exp(-td / 0.065)

    # 3. Tail: Resonant locomotive hull shudder
    shudder = (
        0.52 * np.sin(2.0 * np.pi * 42.0 * t) * np.exp(-t / 0.48)
        + 0.30 * np.sin(2.0 * np.pi * 78.5 * t) * np.exp(-t / 0.36)
    )

    mix = 0.90 * sub_slam + 0.70 * crunch_Burst + screech + debris + shudder
    mix = shape_sfx_edges(mix, fade_in_ms=1.0, fade_out_ms=30.0)
    return normalize_to_dbfs(mix, target_dbfs=-0.9)


def synth_sfx_reactor_alarm():
    """
    sfx_reactor_alarm.wav (~1.50s, 3 urgent 0.50s klaxon pulses):
    Diegetic Reactor Overheat Warning Klaxon (triggered at >=75% thermal threshold):
    - Bi-tonal industrial siren pulse (880 Hz <-> 1320 Hz fifth + gritty brass resonator)
    - Urgent sub-pulse and high harmonic urgency beacon
    """
    set_seed(504)
    dur_sec = 1.50
    n = int(dur_sec * SAMPLE_RATE)
    t = np.arange(n, dtype=np.float64) / SAMPLE_RATE

    pulse_period = 0.50
    t_local = t % pulse_period

    # Bi-tonal pitch sweep within each 0.50s cycle: 880 Hz rising to 1320 Hz then dropping
    f_klaxon = np.where(
        t_local < 0.22,
        880.0 + (1320.0 - 880.0) * (t_local / 0.22),
        1320.0 - (1320.0 - 780.0) * ((t_local - 0.22) / 0.28),
    )
    phase = 2.0 * np.pi * np.cumsum(f_klaxon) / SAMPLE_RATE

    # Pulse envelope with sharp attack and brief inter-pulse gap
    pulse_env = np.where(
        t_local < 0.42,
        (1.0 - np.exp(-t_local / 0.008)) * np.exp(-np.maximum(t_local - 0.28, 0.0) / 0.12),
        0.0,
    )

    # Rich industrial horn waveform (odd harmonics + FM buzz)
    horn = (
        0.55 * np.sin(phase)
        + 0.28 * np.sin(2.0 * phase)
        + 0.22 * np.sin(3.0 * phase)
        + 0.14 * np.sin(5.0 * phase)
    )
    horn_grit = np.tanh(2.1 * horn) * pulse_env

    # Secondary urgent Geiger/thermal relay click on each pulse start
    relay_click = np.sin(2.0 * np.pi * 2600.0 * t_local) * np.exp(-t_local / 0.010)

    mix = 0.85 * horn_grit + 0.25 * relay_click
    mix = shape_sfx_edges(mix, fade_in_ms=2.0, fade_out_ms=15.0)
    return normalize_to_dbfs(mix, target_dbfs=-1.5)


def synth_sfx_track_switch():
    """
    sfx_track_switch.wav (~0.85s):
    Spline Track Junction Lever & Rail Lock:
    - Transient (0.00s): Heavy iron switch-lever throw clank
    - Body (0.08s - 0.42s): Pneumatic cylinder pressure hiss & sliding rail grind
    - Tail (0.42s - 0.85s): Solid steel rail-frog lock transient + metallic rail ring
    """
    set_seed(505)
    dur_sec = 0.85
    n = int(dur_sec * SAMPLE_RATE)
    t = np.arange(n, dtype=np.float64) / SAMPLE_RATE

    # 1. Initial lever throw clank at t = 0.0s
    lever_clank = (
        0.65 * np.sin(2.0 * np.pi * (310.0 + 220.0 * np.exp(-t * 40.0)) * t) * np.exp(-t / 0.045)
        + 0.45 * np.sin(2.0 * np.pi * 890.0 * t) * np.exp(-t / 0.030)
        + 0.40 * fft_bandpass(np.random.randn(n), 600.0, 4500.0) * np.exp(-t / 0.020)
    )

    # 2. Pneumatic actuator hiss & rail slide (t = 0.06s to 0.42s)
    mask_pneu = (t >= 0.06).astype(np.float64)
    tp = np.maximum(t - 0.06, 0.0)
    pneu_env = mask_pneu * (1.0 - np.exp(-tp / 0.015)) * np.exp(-tp / 0.14)
    pneu_hiss = 0.48 * fft_bandpass(np.random.randn(n), 1800.0, 9000.0) * pneu_env
    rail_slide = 0.30 * np.sin(2.0 * np.pi * (180.0 + 140.0 * tp) * tp) * pneu_env

    # 3. Heavy steel rail-lock slam at t = 0.40s
    mask_lock = (t >= 0.40).astype(np.float64)
    tl = np.maximum(t - 0.40, 0.0)
    lock_sub = np.tanh(2.5 * np.sin(2.0 * np.pi * (68.0 + 180.0 * np.exp(-tl * 45.0)) * tl)) * np.exp(-tl / 0.075)
    lock_ring = (
        0.50 * np.sin(2.0 * np.pi * 540.0 * tl) * np.exp(-tl / 0.095)
        + 0.38 * np.sin(2.0 * np.pi * 1260.0 * tl) * np.exp(-tl / 0.070)
        + 0.25 * np.sin(2.0 * np.pi * 2420.0 * tl) * np.exp(-tl / 0.055)
    )
    lock_event = mask_lock * (0.85 * lock_sub + lock_ring)

    mix = lever_clank + pneu_hiss + rail_slide + lock_event
    mix = shape_sfx_edges(mix, fade_in_ms=1.5, fade_out_ms=20.0)
    return normalize_to_dbfs(mix, target_dbfs=-1.2)


# ==============================================================================
# 3. ADAPTIVE AUDIO CUES JSON SPECIFICATION
# ==============================================================================

def build_adaptive_audio_cues_spec(stem_diagnostics, sfx_diagnostics):
    return {
        "schema_id": "chrono-convoy-adaptive-audio-v1",
        "version": "1.0.0-sprint1",
        "ticket_id": "AUD-01",
        "clickup_task_id": "z8rctemh4x",
        "audio_engine_target": "LibGDX Audio (Music streamed stems + Sound pooled SFX)",
        "mastering_standards": {
            "sample_rate_hz": SAMPLE_RATE,
            "bit_depth": 16,
            "channels": 1,
            "stem_bpm": BPM,
            "time_signature": "4/4",
            "loop_bars": BARS,
            "loop_duration_sec": LOOP_DURATION_SEC,
            "loop_length_samples": LOOP_SAMPLES,
            "zero_crossing_verified": True,
            "target_bus_lufs": -14.0,
            "max_true_peak_dbfs": -0.9
        },
        "telemetry_inputs": {
            "convoy_velocity_mps": {
                "source_config": "docs/design/convoy_config.json#/locomotive",
                "stopped_mps": 0.0,
                "min_cruise_speed_mps": 6.0,
                "base_max_speed_mps": 28.0,
                "overclock_max_speed_mps": 38.0,
                "normalization_formula": "v_norm = clamp(convoy_velocity_mps / 38.0, 0.0, 1.0)"
            },
            "reactor_heat": {
                "source_config": "docs/design/convoy_config.json#/reactor/overclock",
                "min_heat": 0.0,
                "thermal_warning_threshold": 75.0,
                "thermal_shutdown_threshold": 100.0,
                "normalization_formula": "heat_norm = clamp(reactor_heat / 100.0, 0.0, 1.0)",
                "states": {
                    "NOMINAL": {"heat_range": [0.0, 59.9], "overclock_active": False},
                    "ELEVATED": {"heat_range": [60.0, 74.9], "overclock_active": True},
                    "WARNING": {"heat_range": [75.0, 99.9], "overclock_active": True, "triggers_alarm": True},
                    "THERMAL_SHUTDOWN": {"heat_range": [100.0, 100.0], "duration_sec": 5.0, "triggers_alarm": True}
                }
            },
            "combat_threat_level": {
                "range": [0.0, 1.0],
                "description": "Normalized nearby raider threat weight sum (0.0 = clear desert, 1.0 = heavy siege / Scrap Canyon)"
            }
        },
        "vertical_layering_stems": [
            {
                "id": "stem_01_desert_idle",
                "file": "stem_01_desert_idle.wav",
                "libgdx_type": "Music",
                "role": "Atmospheric desert wind pad, D1/A1 sub-drone & metallic heat shimmer",
                "loop": True,
                "bpm": BPM,
                "duration_sec": LOOP_DURATION_SEC,
                "samples": LOOP_SAMPLES,
                "base_gain": 0.85,
                "velocity_crossfade_curve": {
                    "input": "v_norm",
                    "interpolation": "smoothstep",
                    "keyframes": [
                        {"v_norm": 0.00, "velocity_mps": 0.0,  "gain": 1.00},
                        {"v_norm": 0.16, "velocity_mps": 6.0,  "gain": 0.88},
                        {"v_norm": 0.50, "velocity_mps": 19.0, "gain": 0.62},
                        {"v_norm": 0.74, "velocity_mps": 28.0, "gain": 0.45},
                        {"v_norm": 1.00, "velocity_mps": 38.0, "gain": 0.35}
                    ]
                },
                "reactor_heat_modulation": {
                    "input": "heat_norm",
                    "keyframes": [
                        {"heat_norm": 0.00, "gain_multiplier": 1.00, "pitch_multiplier": 1.000},
                        {"heat_norm": 0.75, "gain_multiplier": 0.90, "pitch_multiplier": 1.010},
                        {"heat_norm": 1.00, "gain_multiplier": 1.15, "pitch_multiplier": 0.960}
                    ]
                },
                "pitch_shift_curve": {
                    "formula": "pitch = 0.98 + 0.04 * v_norm",
                    "min_pitch": 0.96,
                    "max_pitch": 1.04
                },
                "diagnostics": stem_diagnostics["stem_01_desert_idle.wav"]
            },
            {
                "id": "stem_02_locomotive_chug",
                "file": "stem_02_locomotive_chug.wav",
                "libgdx_type": "Music",
                "role": "Rhythmic 16th-note piston/steam chug and D-Phrygian industrial bass groove",
                "loop": True,
                "bpm": BPM,
                "duration_sec": LOOP_DURATION_SEC,
                "samples": LOOP_SAMPLES,
                "base_gain": 0.92,
                "velocity_crossfade_curve": {
                    "input": "v_norm",
                    "interpolation": "smoothstep",
                    "keyframes": [
                        {"v_norm": 0.00, "velocity_mps": 0.0,  "gain": 0.00},
                        {"v_norm": 0.08, "velocity_mps": 3.0,  "gain": 0.35},
                        {"v_norm": 0.16, "velocity_mps": 6.0,  "gain": 0.68},
                        {"v_norm": 0.74, "velocity_mps": 28.0, "gain": 0.96},
                        {"v_norm": 1.00, "velocity_mps": 38.0, "gain": 1.00}
                    ]
                },
                "reactor_heat_modulation": {
                    "input": "heat_norm",
                    "keyframes": [
                        {"heat_norm": 0.00, "gain_multiplier": 1.00, "pitch_multiplier": 1.000},
                        {"heat_norm": 0.75, "gain_multiplier": 1.05, "pitch_multiplier": 1.035},
                        {"heat_norm": 1.00, "gain_multiplier": 0.40, "pitch_multiplier": 0.880}
                    ]
                },
                "pitch_shift_curve": {
                    "formula": "pitch = 0.92 + 0.16 * v_norm + 0.04 * max(0.0, (heat_norm - 0.60) / 0.40)",
                    "min_pitch": 0.88,
                    "max_pitch": 1.12
                },
                "diagnostics": stem_diagnostics["stem_02_locomotive_chug.wav"]
            },
            {
                "id": "stem_03_raider_percussion",
                "file": "stem_03_raider_percussion.wav",
                "libgdx_type": "Music",
                "role": "Driving industrial war-drums, anvil backbeats, and syncopated metallic percussion",
                "loop": True,
                "bpm": BPM,
                "duration_sec": LOOP_DURATION_SEC,
                "samples": LOOP_SAMPLES,
                "base_gain": 0.90,
                "velocity_crossfade_curve": {
                    "input": "v_norm",
                    "interpolation": "smoothstep",
                    "keyframes": [
                        {"v_norm": 0.00, "velocity_mps": 0.0,  "gain": 0.00},
                        {"v_norm": 0.25, "velocity_mps": 9.5,  "gain": 0.15},
                        {"v_norm": 0.45, "velocity_mps": 17.1, "gain": 0.65},
                        {"v_norm": 0.74, "velocity_mps": 28.0, "gain": 0.92},
                        {"v_norm": 1.00, "velocity_mps": 38.0, "gain": 1.00}
                    ]
                },
                "threat_and_heat_boost": {
                    "formula": "effective_gain = max(velocity_gain, 0.85 * combat_threat_level) * (1.0 + 0.10 * heat_norm)"
                },
                "pitch_shift_curve": {
                    "formula": "pitch = 0.96 + 0.08 * v_norm + 0.03 * heat_norm",
                    "min_pitch": 0.95,
                    "max_pitch": 1.07
                },
                "diagnostics": stem_diagnostics["stem_03_raider_percussion.wav"]
            },
            {
                "id": "stem_04_overdrive_synth",
                "file": "stem_04_overdrive_synth.wav",
                "libgdx_type": "Music",
                "role": "Aggressive distorted D-Phrygian arp/lead synth and reactor overdrive harmonic layer",
                "loop": True,
                "bpm": BPM,
                "duration_sec": LOOP_DURATION_SEC,
                "samples": LOOP_SAMPLES,
                "base_gain": 0.88,
                "velocity_crossfade_curve": {
                    "input": "v_norm",
                    "interpolation": "smoothstep",
                    "keyframes": [
                        {"v_norm": 0.00, "velocity_mps": 0.0,  "gain": 0.00},
                        {"v_norm": 0.55, "velocity_mps": 20.9, "gain": 0.00},
                        {"v_norm": 0.74, "velocity_mps": 28.0, "gain": 0.55},
                        {"v_norm": 0.88, "velocity_mps": 33.4, "gain": 0.88},
                        {"v_norm": 1.00, "velocity_mps": 38.0, "gain": 1.00}
                    ]
                },
                "reactor_heat_crossfade_curve": {
                    "input": "heat_norm",
                    "interpolation": "smoothstep",
                    "keyframes": [
                        {"heat_norm": 0.00, "reactor_heat": 0.0,   "gain_floor": 0.00, "pitch_shift_semitones": 0.0},
                        {"heat_norm": 0.55, "reactor_heat": 55.0,  "gain_floor": 0.20, "pitch_shift_semitones": 0.0},
                        {"heat_norm": 0.75, "reactor_heat": 75.0,  "gain_floor": 0.78, "pitch_shift_semitones": 0.5},
                        {"heat_norm": 0.92, "reactor_heat": 92.0,  "gain_floor": 0.96, "pitch_shift_semitones": 1.2},
                        {"heat_norm": 1.00, "reactor_heat": 100.0, "gain_floor": 1.00, "pitch_shift_semitones": 2.0}
                    ],
                    "combined_gain_formula": "gain = clamp(max(velocity_gain, heat_gain_floor), 0.0, 1.0)"
                },
                "pitch_shift_curve": {
                    "formula": "pitch = 1.00 + 0.06 * max(0.0, (v_norm - 0.60) / 0.40) + 0.06 * max(0.0, (heat_norm - 0.55) / 0.45)",
                    "min_pitch": 1.00,
                    "max_pitch": 1.12
                },
                "diagnostics": stem_diagnostics["stem_04_overdrive_synth.wav"]
            }
        ],
        "crossfade_smoothing": {
            "attack_slew_rate_per_sec": 1.8,
            "release_slew_rate_per_sec": 0.85,
            "sync_lock_mode": "PHASE_LOCKED_MASTER_CLOCK",
            "master_clock_stem": "stem_01_desert_idle",
            "resync_drift_threshold_ms": 8.0
        },
        "priority_ducking_and_buses": {
            "buses": {
                "MASTER": {"default_gain": 1.0, "limiter_ceiling_dbfs": -0.5},
                "MUSIC_STEMS": {"default_gain": 0.82, "ducked_gain_combat": 0.62, "ducked_gain_alarm": 0.50},
                "COMBAT_SFX": {"default_gain": 0.95, "max_voices": 12},
                "CRITICAL_ALERTS": {"default_gain": 1.00, "max_voices": 4}
            },
            "ducking_rules": [
                {
                    "trigger_sfx": "sfx_heavy_ram_impact",
                    "target_bus": "MUSIC_STEMS",
                    "attenuation_db": -4.5,
                    "attack_ms": 15,
                    "hold_ms": 350,
                    "release_ms": 600
                },
                {
                    "trigger_sfx": "sfx_reactor_alarm",
                    "target_bus": "MUSIC_STEMS",
                    "attenuation_db": -5.0,
                    "attack_ms": 25,
                    "hold_ms": 1200,
                    "release_ms": 500
                },
                {
                    "trigger_sfx": "sfx_artillery_fire",
                    "target_bus": "MUSIC_STEMS",
                    "attenuation_db": -2.5,
                    "attack_ms": 10,
                    "hold_ms": 200,
                    "release_ms": 400
                }
            ]
        },
        "combat_sfx_cues": {
            "sfx_artillery_fire": {
                "file": "sfx_artillery_fire.wav",
                "libgdx_type": "Sound",
                "bus": "COMBAT_SFX",
                "priority": 85,
                "max_concurrent_voices": 4,
                "cooldown_ms": 180,
                "default_volume": 0.92,
                "volume_randomization": [-0.05, 0.05],
                "pitch_randomization": [0.94, 1.06],
                "watt_scaling": {
                    "input": "artillery_watts",
                    "pitch_formula": "pitch = base_pitch * (0.95 + 0.002 * artillery_watts)",
                    "volume_formula": "vol = clamp(0.82 + 0.003 * artillery_watts, 0.80, 1.00)"
                },
                "spatial_attenuation": {"ref_distance_m": 18.0, "max_distance_m": 160.0, "rolloff_factor": 1.0},
                "diagnostics": sfx_diagnostics["sfx_artillery_fire.wav"]
            },
            "sfx_shield_deflect": {
                "file": "sfx_shield_deflect.wav",
                "libgdx_type": "Sound",
                "bus": "COMBAT_SFX",
                "priority": 80,
                "max_concurrent_voices": 5,
                "cooldown_ms": 90,
                "default_volume": 0.88,
                "volume_randomization": [-0.06, 0.04],
                "pitch_randomization": [0.92, 1.10],
                "shield_ratio_modulation": {
                    "input": "shield_hp_ratio",
                    "pitch_formula": "pitch = 0.88 + 0.20 * shield_hp_ratio"
                },
                "spatial_attenuation": {"ref_distance_m": 15.0, "max_distance_m": 120.0, "rolloff_factor": 1.1},
                "diagnostics": sfx_diagnostics["sfx_shield_deflect.wav"]
            },
            "sfx_heavy_ram_impact": {
                "file": "sfx_heavy_ram_impact.wav",
                "libgdx_type": "Sound",
                "bus": "COMBAT_SFX",
                "priority": 95,
                "max_concurrent_voices": 2,
                "cooldown_ms": 250,
                "default_volume": 0.98,
                "volume_randomization": [-0.03, 0.02],
                "pitch_randomization": [0.93, 1.04],
                "impulse_scaling": {
                    "input": "relative_impact_speed_mps",
                    "volume_formula": "vol = clamp(0.65 + 0.012 * relative_impact_speed_mps, 0.65, 1.00)",
                    "pitch_formula": "pitch = clamp(1.05 - 0.004 * relative_impact_speed_mps, 0.88, 1.05)"
                },
                "spatial_attenuation": {"ref_distance_m": 25.0, "max_distance_m": 180.0, "rolloff_factor": 0.85},
                "diagnostics": sfx_diagnostics["sfx_heavy_ram_impact.wav"]
            },
            "sfx_reactor_alarm": {
                "file": "sfx_reactor_alarm.wav",
                "libgdx_type": "Sound",
                "bus": "CRITICAL_ALERTS",
                "priority": 100,
                "max_concurrent_voices": 1,
                "cooldown_ms": 1500,
                "default_volume": 0.90,
                "volume_randomization": [0.0, 0.0],
                "pitch_randomization": [1.0, 1.0],
                "thermal_escalation": {
                    "trigger_threshold_heat": 75.0,
                    "shutdown_threshold_heat": 100.0,
                    "pitch_formula": "pitch = 1.0 + 0.12 * clamp((reactor_heat - 75.0) / 25.0, 0.0, 1.0)"
                },
                "spatial_attenuation": {"diegetic_2d_cockpit": True},
                "diagnostics": sfx_diagnostics["sfx_reactor_alarm.wav"]
            },
            "sfx_track_switch": {
                "file": "sfx_track_switch.wav",
                "libgdx_type": "Sound",
                "bus": "CRITICAL_ALERTS",
                "priority": 90,
                "max_concurrent_voices": 2,
                "cooldown_ms": 300,
                "default_volume": 0.90,
                "volume_randomization": [-0.04, 0.04],
                "pitch_randomization": [0.97, 1.03],
                "spatial_attenuation": {"diegetic_2d_cockpit": True},
                "diagnostics": sfx_diagnostics["sfx_track_switch.wav"]
            }
        }
    }


def main():
    source_dir = "/workspace/chrono-convoy-iron-drift/assets/audio"
    client_dir = "/workspace/chrono-convoy-iron-drift/client/assets/audio"
    os.makedirs(source_dir, exist_ok=True)
    os.makedirs(client_dir, exist_ok=True)

    stems = {
        "stem_01_desert_idle.wav": synth_stem_01_desert_idle(),
        "stem_02_locomotive_chug.wav": synth_stem_02_locomotive_chug(),
        "stem_03_raider_percussion.wav": synth_stem_03_raider_percussion(),
        "stem_04_overdrive_synth.wav": synth_stem_04_overdrive_synth(),
    }

    sfxs = {
        "sfx_artillery_fire.wav": synth_sfx_artillery_fire(),
        "sfx_shield_deflect.wav": synth_sfx_shield_deflect(),
        "sfx_heavy_ram_impact.wav": synth_sfx_heavy_ram_impact(),
        "sfx_reactor_alarm.wav": synth_sfx_reactor_alarm(),
        "sfx_track_switch.wav": synth_sfx_track_switch(),
    }

    stem_diagnostics = {}
    for fname, sig in stems.items():
        src_path = os.path.join(source_dir, fname)
        cli_path = os.path.join(client_dir, fname)
        diag = write_wav_16bit(src_path, sig)
        shutil.copy2(src_path, cli_path)
        stem_diagnostics[fname] = diag

    sfx_diagnostics = {}
    for fname, sig in sfxs.items():
        src_path = os.path.join(source_dir, fname)
        cli_path = os.path.join(client_dir, fname)
        diag = write_wav_16bit(src_path, sig)
        shutil.copy2(src_path, cli_path)
        sfx_diagnostics[fname] = diag

    cues_spec = build_adaptive_audio_cues_spec(stem_diagnostics, sfx_diagnostics)
    for target_folder in (source_dir, client_dir):
        cues_path = os.path.join(target_folder, "adaptive_audio_cues.json")
        with open(cues_path, "w", encoding="utf-8") as f:
            json.dump(cues_spec, f, indent=2)
            f.write("\n")

    print("=== CHRONO-CONVOY SPRINT 1 AUDIO SYNTHESIS COMPLETE ===")
    print(f"Sample Rate: {SAMPLE_RATE} Hz | Bit Depth: 16-bit PCM | BPM: {BPM} | Loop Samples: {LOOP_SAMPLES}")
    print("\n--- Stems ---")
    for k, v in stem_diagnostics.items():
        print(f"  {k:30s} | {v['duration_sec']:6.3f}s ({v['samples']} samples) | Peak: {v['peak_dbfs']:6.2f} dBFS | RMS: {v['rms_dbfs']:6.2f} dBFS | Boundary: [{v['start_sample_int16']}, {v['end_sample_int16']}]")
    print("\n--- Combat & Diegetic SFX ---")
    for k, v in sfx_diagnostics.items():
        print(f"  {k:30s} | {v['duration_sec']:6.3f}s ({v['samples']} samples) | Peak: {v['peak_dbfs']:6.2f} dBFS | RMS: {v['rms_dbfs']:6.2f} dBFS | Boundary: [{v['start_sample_int16']}, {v['end_sample_int16']}]")


if __name__ == "__main__":
    main()
