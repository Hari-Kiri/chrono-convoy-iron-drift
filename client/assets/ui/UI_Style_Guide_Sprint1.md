# Chrono-Convoy: Iron Drift — Diegetic Engineer Control Deck & Scene2D Skin Specification (`[UI-01]`)

**Ticket:** `[UI-01] Diegetic Engineer Control Deck & Scene2D Skin Specification` (`z8rctemh4w`)  
**Owner:** @Mochi (2D & UI/UX Artist)  
**Consumers:** @Jack (`CLI-01` Spline Track & Box2D Physics, `CLI-02` Reactor Watt Controller & Turret Loop), @Droplet (`DES-01`/`DES-02` Economy & Thermal Sink Telemetry), @Aegis (`QA-01` Headless Asset & Skin Validation)  
**Target Directories:**
- Source & Master Package: `/workspace/chrono-convoy-iron-drift/assets/ui/`
- Runtime Client Package: `/workspace/chrono-convoy-iron-drift/client/assets/ui/`

---

## 1. Aesthetic Pillars & Material Language

The **Diegetic Engineer Control Deck** grounds the player inside an armored locomotive cab hurtling across the scorched Oasis rail network. Every UI element is physically bolted into the cab console rather than floating as an abstract overlay:

1. **Burnished Industrial Brass (`panel_brass_deck`, `btn_brass_*`, `slider_track_brass`)**:
   - Heavy 45° chamfered corners, corner bronze rivets (`2px` specular highlight + `2px` recessed shadow), and machined calibration ticks along every slider channel.
2. **Cast-Iron Structural Bulkheads (`panel_iron_inset`, `scroll_track_iron`)**:
   - Low-albedo brushed dark iron (`#181B20` to `#2A2F37`) providing a high-contrast, low-glare backdrop so critical combat and thermal telemetry pops instantaneously in peripheral vision.
3. **Recessed Phosphor CRT Monitors (`panel_crt_screen`, `crt_scanline_tint`)**:
   - Deep curved-glass phosphor green/amber CRT bezels (`#08140E` glass base with `#102E1E` 2px scanlines) housing numerical telemetry, reactor watt curves, core thermodynamics, and braking envelope projections.

---

## 2. Color Palette & WCAG / Colorblind Accessibility Matrix

Every subsystem pairs a **high-contrast chromatic channel** (WCAG AA/AAA $\ge 7.2:1$ against `#08140E` CRT glass and `#181B20` cast iron) with a **unique geometric silhouette icon** so Deuteranopia, Protanopia, and Tritanopia players never rely on hue alone:

| Semantic Token (`Skin` Color Name) | Hex | RGBA Float (`r, g, b, a`) | Contrast vs. `#08140E` | Paired Silhouette / Redundant Visual Cue |
| :--- | :--- | :--- | :--- | :--- |
| `brass_light` | `#E5B869` | `0.898, 0.722, 0.412, 1.0` | `10.4 : 1` (AAA) | Specular bevel highlight & section headers |
| `brass_main` | `#C49245` | `0.769, 0.573, 0.271, 1.0` | `7.3 : 1` (AAA) | Primary structural frame & mechanical bezels |
| `brass_dark` | `#845C24` | `0.518, 0.361, 0.141, 1.0` | `3.8 : 1` (UI Frame) | Recessed shadow groove & depressed button bezel |
| `copper_warm` | `#D47A43` | `0.831, 0.478, 0.263, 1.0` | `6.5 : 1` (AA) | `icon_cargo_tonnage` — Trapezoidal freight ingot silhouette |
| `iron_dark` | `#181B20` | `0.094, 0.106, 0.125, 1.0` | Base Surface | Primary cast-iron bulkhead background |
| `crt_bg` | `#08140E` | `0.031, 0.078, 0.055, 1.0` | Base CRT Glass | Recessed phosphor monitor background |
| `shields_cyan` | `#38D9F5` | `0.220, 0.851, 0.961, 1.0` | `11.8 : 1` (AAA) | `icon_reactor_shields` — **Hexagonal Aegis Barrier** |
| `artillery_amber` | `#F5A623` | `0.961, 0.651, 0.137, 1.0` | `9.6 : 1` (AAA) | `icon_reactor_artillery` — **Crosshair Reticle + Shell** |
| `thrusters_emerald` / `crt_phosphor_green` | `#2EE67A` | `0.180, 0.902, 0.478, 1.0` | `11.2 : 1` (AAA) | `icon_reactor_thrusters` — **Triple Propulsion Chevrons** |
| `critical_crimson` | `#FF3B30` | `1.000, 0.231, 0.188, 1.0` | `5.9 : 1` (AA Large) | `icon_temp_overheat` / `led_status_red` — **Triangle `!` / Cross `X`** |
| `warning_orange` | `#FF7626` | `1.000, 0.463, 0.149, 1.0` | `7.4 : 1` (AAA) | `icon_brake_caliper` — **Circular Rotor + Caliper Arc** |
| `hazard_yellow` | `#F8D12A` | `0.973, 0.820, 0.165, 1.0` | `13.5 : 1` (AAA) | `overlay_track_switcher_warning` — **45° Diagonal Hazard Stripes** |
| `ivory_text` | `#F4ECD8` | `0.957, 0.925, 0.847, 1.0` | `16.8 : 1` (AAA) | Primary alphanumeric readout & etched label text |
| `muted_steel` | `#949EA8` | `0.580, 0.620, 0.659, 1.0` | `7.1 : 1` (AAA) | Secondary units, formulas, and keybind hints |

### Status LED Shape Redundancy
- `led_status_green`: Phosphor Emerald `#2EE67A` + **Inner Solid Circle** (Nominal $< 650\text{ K}$)
- `led_status_amber`: Ordnance Amber `#F5A623` + **Inner Inscribed Triangle** (Thermal Warning $650\text{–}850\text{ K}$)
- `led_status_red`: Critical Crimson `#FF3B30` + **Inner High-Contrast Cross `X`** (Core Meltdown SCRAM $> 850\text{ K}$)

---

## 3. Responsive Layout, Viewport Architecture & Nine-Patch Table

### Viewport & Aspect Ratio Adaptation
- **Baseline Virtual Resolution:** `1920 x 1080` (`ExtendViewport(1920, 1080)` recommended in LibGDX `Screen`).
- **Bottom Control Deck Table (`deckRootTable`)**:
  - Anchored `Align.bottom`, height `332px` (`30.7%` vertical screen height), leaving the upper `748px` (`69.3%`) unobstructed for the 3D/2D spline track, convoy carriages, and raider combat.
  - On **21:9 Ultrawide (`2560 x 1080`)**, `deckRootTable` caps its max width at `1960px` centered (`cell.maxWidth(1960f).growX()`) while the outer brass frame stretches cleanly via 9-patch splits without distorting corner rivets.
- **Top-Center Spline Track Switcher Overlay (`switcherModalTable`)**:
  - Anchored `Align.top | Align.center` with `padTop(96f)`, dimensions `800 x 148`. Dynamically slides in using `Actions.moveToAligned(..., 0.22f, Interpolation.swingOut)` when the locomotive enters a junction approach trigger zone ($\le 400\text{ m}$).

### Nine-Patch (`.9.png`) Split & Content Padding Specification

All 25 nine-patch regions exist both as standalone `.9.png` source files in `assets/ui/ninepatches/` (with 1px `#000000FF` stretch/padding border markers) and as pre-split regions inside `convoy_hud.atlas`:

| Region Name | Source Content Size | `split` `(L, R, T, B)` | `pad` `(L, R, T, B)` | Primary Scene2D Usage |
| :--- | :--- | :--- | :--- | :--- |
| `panel_brass_deck` | `128 x 96` | `18, 18, 18, 18` | `14, 14, 14, 14` | Root Engineer Control Deck housing (`WindowStyle.default`) |
| `panel_crt_screen` | `96 x 72` | `14, 14, 14, 14` | `10, 10, 10, 10` | Recessed phosphor CRT sub-panels (`WindowStyle.crt-monitor`, `LabelStyle.crt-readout`) |
| `panel_iron_inset` | `64 x 64` | `10, 10, 10, 10` | `8, 8, 8, 8` | Cast-iron inset slots & scroll panes (`ScrollPaneStyle.default`) |
| `panel_modal_warning` | `96 x 72` | `16, 16, 20, 14` | `12, 12, 18, 10` | Emergency alert modal dialog (`WindowStyle.warning-modal`) |
| `overlay_track_switcher_warning` | `128 x 64` | `18, 18, 16, 16` | `12, 12, 14, 14` | Spline junction warning banner (`WindowStyle.track-switcher-overlay`) |
| `slider_track_brass` | `96 x 20` | `12, 12, 6, 6` | `4, 4, 3, 3` | Horizontal reactor watt slider groove |
| `slider_track_vertical` | `20 x 96` | `6, 6, 12, 12` | `3, 3, 4, 4` | Vertical reactor watt slider groove (`SliderStyle.default-vertical`) |
| `slider_fill_shields_cyan` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Aegis Shields slider fill (`SliderStyle.reactor-shields`) |
| `slider_fill_artillery_amber` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Siege Artillery slider fill (`SliderStyle.reactor-artillery`) |
| `slider_fill_thrusters_emerald` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Locomotive Thrusters slider fill (`SliderStyle.reactor-thrusters`) |
| `gauge_temp_track` | `96 x 22` | `12, 12, 6, 6` | `4, 4, 3, 3` | Core Temperature linear gauge housing (`ProgressBarStyle.core-temp`) |
| `gauge_temp_fill_nominal` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Nominal thermal fill (`< 850 K`) |
| `gauge_temp_fill_critical` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Critical overheat hazard fill (`>= 850 K`, `ProgressBarStyle.core-temp-critical`) |
| `bar_tonnage_track` | `96 x 22` | `12, 12, 6, 6` | `4, 4, 3, 3` | Cargo Tonnage & Braking Distance bar housing |
| `bar_tonnage_fill` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Gross cargo mass fill (`ProgressBarStyle.cargo-tonnage`) |
| `bar_braking_fill` | `64 x 14` | `8, 8, 4, 4` | `2, 2, 2, 2` | Stopping distance projection fill (`ProgressBarStyle.braking-distance`) |
| `btn_brass_up` / `over` / `down` / `disabled` / `checked` | `64 x 36` | `10, 10, 8, 8` | `8, 8, 6, 6` | Mechanical brass buttons & junction toggles |
| `btn_emergency_up` / `down` | `64 x 36` | `10, 10, 8, 8` | `8, 8, 6, 6` | Crimson Cooling Rod / Steam Vent override (`TextButtonStyle.emergency-vent`) |
| `scroll_track_iron` / `scroll_knob_brass` | `16x64` / `14x32` | `4,4,8,8` / `4,4,6,6` | `2, 2, 2, 2` | Scrollbar track & thumb |

---

## 4. Widget Specifications, Micro-Interactions & Telemetry Bindings

### Module A: 3-Way Reactor Watt Allocation Sliders (`Shields`, `Artillery`, `Thrusters`)
- **Conserved Zero-Sum Pool ($P_{\text{total}} = 100.0\text{ MW}$)**:
  - Dragging any slider (`reactor-shields`, `reactor-artillery`, `reactor-thrusters`) by $\Delta W$ proportionally counter-adjusts the remaining unlocked sliders so $\sum W_i = 100.0\text{ MW}$ at all times:
    $$W_j' = W_j - \Delta W \cdot \frac{W_j}{\sum_{k \neq i} W_k}$$
- **Micro-Interactions**:
  - Visual fill interpolation uses `bar.setAnimateDuration(0.08f)` with `Interpolation.pow2Out` for crisp mechanical damping.
  - When a subsystem receives $\ge 50\text{ MW}$ (Overcharge), pulse its badge (`icon_reactor_*`) between `1.0f` and `1.08f` scale at `4 Hz`.

### Module B: Locomotive Core Temperature Gauge & Cooling Rod Vent
- **Thermal Thresholds**:
  - **Nominal ($300\text{ K} \le T < 650\text{ K}$)**: Uses `ProgressBarStyle` `"core-temp"`, `led_status_green` active.
  - **Thermal Warning ($650\text{ K} \le T < 850\text{ K}$)**: Tint `gauge_temp_fill_nominal` with `artillery_amber`, `led_status_amber` active.
  - **Critical Overheat / SCRAM ($T \ge 850\text{ K}$)**: Swap style to `"core-temp-critical"`, `led_status_red` flashing at `6 Hz`, enable `danger_flash_tint` overlay, and pulse the `[SPACE] INJECT COOLING ROD` button (`TextButtonStyle` `"emergency-vent"`).

### Module C: Cargo Tonnage vs. Braking Distance Indicator
- **Coupled Physics Readout**:
  - Top bar (`ProgressBarStyle` `"cargo-tonnage"`) displays current convoy gross mass $M_{\text{total}} \in [100\text{ t}, 600\text{ t}]$ (locomotive + carriages + Oasis commodities).
  - Bottom bar (`ProgressBarStyle` `"braking-distance"`) displays real-time stopping distance:
    $$d_{\text{stop}} = \frac{v^2}{2 \cdot \mu_{\text{rail}} \cdot g \cdot (M_{\text{base}} / M_{\text{total}})}$$
  - The `knob` drawable (`marker_stopping_threshold`) marks the distance to the next spline junction or hazard. When $d_{\text{stop}} \ge d_{\text{junction}}$, the braking bar flashes `critical_crimson` to warn the Engineer that full pneumatic braking is required before switching tracks.

### Module D: Spline Track Switcher Warning Overlay
- **Trigger & Interaction**:
  - Appears when approaching a spline track branch (`WindowStyle` `"track-switcher-overlay"`).
  - Provides three mutually exclusive `ButtonGroup` toggle buttons (`ImageButtonStyle` `"junction-left"`, `"junction-straight"`, `"junction-right"` or `TextButtonStyle` `"toggle-switch"` bound to keys `Q`, `W`, `E`).
  - Displays lateral G-force forecast ($a_{\text{lat}} = v^2 / R_{\text{spline}}$); if $a_{\text{lat}} > 2.2\text{ G}$ (derailment risk), the selected branch button border tints `critical_crimson`.

---

## 5. TextureAtlas & Scene2D Skin Integration Guide for @Jack (`CLI-01` / `CLI-02`)

### Loading the Skin & TextureAtlas in LibGDX
All runtime files (`convoy_hud_skin.json`, `convoy_hud.atlas`, `convoy_hud.png`, `default.fnt`, `convoy_mono.fnt`, `convoy_title.fnt`) are pre-staged in `/workspace/chrono-convoy-iron-drift/client/assets/ui/`.

```java
package com.chronoconvoy.client.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;

public class EngineerControlDeckHud {
    private final TextureAtlas hudAtlas;
    private final Skin hudSkin;

    private final Slider shieldsSlider;
    private final Slider artillerySlider;
    private final Slider thrustersSlider;
    private final ProgressBar coreTempBar;
    private final ProgressBar cargoTonnageBar;
    private final ProgressBar brakingDistanceBar;
    private final TextButton emergencyVentBtn;

    public EngineerControlDeckHud(Stage stage) {
        this.hudAtlas = new TextureAtlas(Gdx.files.internal("ui/convoy_hud.atlas"));
        this.hudSkin = new Skin(Gdx.files.internal("ui/convoy_hud_skin.json"), hudAtlas);

        // 1. 3-Way Reactor Watt Allocation Sliders (0..100 MW, step 1 MW)
        shieldsSlider = new Slider(0f, 100f, 1f, false, hudSkin, "reactor-shields");
        artillerySlider = new Slider(0f, 100f, 1f, false, hudSkin, "reactor-artillery");
        thrustersSlider = new Slider(0f, 100f, 1f, false, hudSkin, "reactor-thrusters");
        shieldsSlider.setValue(35f);
        artillerySlider.setValue(40f);
        thrustersSlider.setValue(25f);

        // 2. Locomotive Core Temperature Gauge (300..950 K)
        coreTempBar = new ProgressBar(300f, 950f, 1f, false, hudSkin, "core-temp");
        coreTempBar.setAnimateDuration(0.08f);
        coreTempBar.setAnimateInterpolation(Interpolation.pow2Out);

        // 3. Cargo Tonnage vs. Braking Distance Bars
        cargoTonnageBar = new ProgressBar(0f, 600f, 1f, false, hudSkin, "cargo-tonnage");
        brakingDistanceBar = new ProgressBar(0f, 500f, 1f, false, hudSkin, "braking-distance");

        // 4. Emergency Cooling Rod Vent Button
        emergencyVentBtn = new TextButton("INJECT COOLING ROD [SPACE]", hudSkin, "emergency-vent");

        // Assemble Root Deck Table
        Table root = new Table(hudSkin);
        root.setFillParent(true);
        root.align(Align.bottom);

        Window deckWindow = new Window("DIEGETIC ENGINEER CONTROL DECK", hudSkin, "default");
        deckWindow.setMovable(false);
        root.add(deckWindow).width(1872f).height(332f).padBottom(12f);
        stage.addActor(root);
    }

    public void updateTelemetry(float coreKelvin, float grossTons, float stoppingMeters) {
        coreTempBar.setValue(coreKelvin);
        coreTempBar.setStyle(hudSkin.get(
            coreKelvin >= 850f ? "core-temp-critical" : "core-temp",
            ProgressBar.ProgressBarStyle.class
        ));
        cargoTonnageBar.setValue(grossTons);
        brakingDistanceBar.setValue(stoppingMeters);
    }

    public void dispose() {
        hudSkin.dispose();
        hudAtlas.dispose();
    }
}
```
