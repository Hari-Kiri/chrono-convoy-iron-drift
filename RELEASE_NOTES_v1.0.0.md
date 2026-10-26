# Chrono-Convoy: Iron Drift — Official Release Notes (`v1.0.0`)

- **Release Version:** `v1.0.0` (`chrono-convoy-iron-drift-config-v1`)
- **Release Date:** Monday, October 26, 2026 (UTC+7)
- **Repository Root:** `/workspace/chrono-convoy-iron-drift/`
- **Release Status:** **GOLD MASTER / QA SIGNED OFF (`100%` Automated Test Pass Rate)**

---

## 1. Executive Summary

**Chrono-Convoy: Iron Drift (`v1.0.0`)** is a real-time tactical convoy combat and dynamic Oasis market trading game built on a deterministic **60Hz LibGDX + Box2D client engine** paired with an authoritative **Go Fiber v2 + Actor Supervision + PostgreSQL 17.11 ACID backend**. Players command the **Iron Drifter Mk.I Armored Locomotive** and up to four modular gun, shield, smelter, and mortar carriages across hazardous desert railway sectors, balancing a **100W base / 130W overclocked nuclear-steam reactor**, fending off high-velocity raider packs, and trading commodities across three regional Oasis Hubs (**Hydro-Haven**, **Iron-Refinery**, and **Aether-Spire**).

---

## 2. Core Gameplay Systems & Parametric Architecture (`SYS-01` / `CLIENT-01`)

- **Deterministic 60Hz Fixed-Timestep Simulation (`dt = 1/60s = 0.0166667s`)**:
  - Decouples rendering from physics using a clamped frame-time accumulator (`max_frame_delta = 0.25s`), `8` velocity iterations, and `3` position iterations per step.
  - Multi-carriage trains are linked via hybrid **Box2D `RevoluteJoint` + `DistanceJoint` couplers** (`frequency_hz = 8.5`, `damping_ratio = 0.82`, angular limit `±24°` to `±38°`) along procedural `CatmullRomSpline` desert rail networks with dynamic track-switching between **Scrap Canyon Trench (High Threat / High Yield)** and **Alkali Salt Flats (Low Threat / High Speed)**.
  - Continuous Collision Detection (`bullet = true`) enabled on high-velocity artillery projectiles and ramming raiders, preventing barrier tunneling up to `450 m/s`.
- **100W / 130W Reactor Power Routing & Thermal Overclocking**:
  - Strict integer/fixed-point watt conservation across **Thermal Shields** (`0–60W`), **Artillery Turrets** (`0–60W`), and **Propulsion Thrusters** (`0–60W`).
  - **Overclocking (`OVERCLOCK_ROD`)** injects `+30W` (`130W` total pool) for `20.0s` while accumulating reactor heat (`6.5 heat/s`). Emergency **Cooling Rod Flush (`FLUSH_COOLING_ROD`)** vents `45.0` heat to prevent the `100.0` heat thermal shutdown threshold.
- **Zero-GC Combat Entity Pooling**:
  - Pre-allocated LibGDX `Pool<T>` instances for projectiles, raiders, damage floaters, and particle emitters ensure zero runtime heap allocations inside `render(delta)`.

---

## 3. Studio Asset Pipeline (`3D-01`, `2D-01`, `AUD-01`)

All production assets are staged and verified inside `/workspace/chrono-convoy-iron-drift/client/assets/`:

### 3.1 3D Low-Poly Dieselpunk Vehicle & Carriage Meshes (`client/assets/3d/`)
Generated via headless Blender 4.3.2 (`GN_ArmorRivets` Geometry Nodes procedural rivet pipeline) with `.obj`, `.mtl`, and binary glTF 2.0 `.glb` exports (`+Z` forward, `+Y` up, `+X` right, strict `<= 3,500` triangles/asset budget):

| Asset ID | Files (`.obj` / `.mtl` / `.glb`) | Vertices (`v`) | UVs (`vt`) | Normals (`vn`) | Triangles (`f`) | Budget (`<= 3,500`) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| `locomotive` | `locomotive.{obj,mtl,glb}` | `940` | `1,092` | `647` | `1,572` | **PASS (`44.9%`)** |
| `carriage_artillery` | `carriage_artillery.{obj,mtl,glb}` | `632` | `732` | `460` | `1,064` | **PASS (`30.4%`)** |
| `carriage_cargo` | `carriage_cargo.{obj,mtl,glb}` | `576` | `648` | `436` | `976` | **PASS (`27.9%`)** |
| `raider_buggy` | `raider_buggy.{obj,mtl,glb}` | `474` | `554` | `329` | `812` | **PASS (`23.2%`)** |
| `raider_harpoon_rig` | `raider_harpoon_rig.{obj,mtl,glb}` | `742` | `860` | `519` | `1,252` | **PASS (`35.8%`)** |

### 3.2 2D Tactical HUD Atlas, Nine-Patches & Scene2D Skin (`client/assets/ui/`)
- **Texture Atlas:** `convoy_hud.atlas` + `convoy_hud.png` (`1024x1024` `RGBA8888`, `Nearest` filtering, `2px` padding, 32 packed regions including 14 Nine-Patch frames with `split:` & `pad:` metadata).
- **Scene2D Skin:** `convoy_hud_skin.json` + 3 custom bitmap fonts (`default.fnt`, `convoy_mono.fnt`, `convoy_title.fnt`) defining the Industrial Brass & Oxidized Iron palette with Phosphor-Green CRT telemetry readouts.

### 3.3 Adaptive 4-Stem Vertical Music & 5 Combat SFX Pipeline (`client/assets/audio/`)
- **Adaptive Music Stems (`44.1 kHz`, `16-bit PCM`, Mono, `120 BPM`, `8.000s` / `352,800` exact samples, zero-crossing loop seams):**
  1. `stem_01_desert_idle.wav` — Atmospheric desert wind pad & D1/A1 sub-drone.
  2. `stem_02_locomotive_chug.wav` — Velocity-linked 16th-note piston/steam chug & D-Phrygian bass.
  3. `stem_03_raider_percussion.wav` — Combat-threat metallic anvil & taiko war percussion.
  4. `stem_04_overdrive_synth.wav` — High-velocity / critical reactor heat overdrive lead synth.
- **Combat & Telemetry SFX (`44.1 kHz`, `16-bit PCM`):**
  1. `sfx_artillery_fire.wav` (`1.65s`, `72,765` samples)
  2. `sfx_heavy_ram_impact.wav` (`1.45s`, `63,945` samples)
  3. `sfx_reactor_alarm.wav` (`1.50s`, `66,150` samples)
  4. `sfx_shield_deflect.wav` (`0.95s`, `41,895` samples)
  5. `sfx_track_switch.wav` (`0.85s`, `37,485` samples)

---

## 4. Backend Architecture, Actor Economy & PostgreSQL 17.11 Ledger (`BE-01` / `BE-02`)

- **Regional Oasis Hub Market Actor Tree (`backend/internal/actor/`)**:
  - `RegionMarketSupervisor` supervises three bounded-mailbox (`capacity = 256`) Oasis Hub actors (`hydro_haven`, `iron_refinery`, `aether_spire`) with a `OneForOne` fault-recovery strategy and a deterministic `60s` supply/demand price recalculation tick.
- **Cryptographic Replay Verification (`backend/internal/replay/`)**:
  - `POST /api/v1/convoy/run/verify` (and `/api/v1/run/verify`) validates canonical `v1|...` action logs using **`HMAC-SHA256`** and **`AES-256-GCM` AEAD** (with Anti-IDOR Associated Data `AAD = v1|run_id|player_id|convoy_id|seed`), re-simulating the 60Hz run server-side to prevent reward tampering, watt over-allocation, or tick manipulation.
- **PostgreSQL 17.11 ACID Persistence (`chrono_convoy` database)**:
  - 5 normalized tables with strict `CHECK` constraints, `BIGINT` versioned optimistic locking, `SELECT ... FOR UPDATE` row locking, and `UNIQUE (idempotency_key)` deduplication:
    - `player_wallets`
    - `convoy_loadouts`
    - `convoy_cargo_inventory`
    - `commodity_ledger_entries`
    - `verified_convoy_runs`

---

## 5. `v1.0.0` QA Verification Summary (`QA-01` & `QA-02`)

| Verification Suite | Command | Total Tests | Passed | Failed | Status |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Java 21 / JUnit 5 Headless LibGDX + Box2D + Economy + Release Asset Suite** | `cd qa && ./gradlew test` | **31** | **31** | **0** | **100% PASS** |
| **Go 1.27 Replay Stress, Fuzz, AEAD Contract & Live E2E PostgreSQL Suite** | `cd qa && go test -v -race ./...` | **13** | **13** | **0** | **100% PASS** |
| **Go 1.27 Backend Unit & Integration Suite** | `cd backend && go test -v ./...` | **9** | **9** | **0** | **100% PASS** |
