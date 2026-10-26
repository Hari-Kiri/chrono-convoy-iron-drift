# Chrono-Convoy: Iron Drift (`v1.0.0`) — Player's Guide & Operations Manual

Welcome aboard the **Iron Drifter Mk.I**, Engineer. This manual covers how to launch the game, compile the client and backend from source, operate the **100W/130W Nuclear-Steam Reactor**, survive raider ambushes, and profit across the **Oasis Hub Commodity Exchange**.

---

## 1. Launching the Game

### Option A: One-Command Full Stack Launch (Recommended)
From the repository root (`/workspace/chrono-convoy-iron-drift`):
```bash
./run-chrono-convoy.sh
```
This script automatically verifies PostgreSQL 17 (`chrono_convoy`), starts the Go backend (`http://127.0.0.1:8080`), ensures `client/build/libs/chrono-convoy-desktop-1.0.0.jar` is built, and opens the `1280x720` 60FPS 3D desktop game window.

### Option B: Manual Backend + Compiled Fat JAR Launch
1. **Start the Go Backend (`backend/`)**:
   ```bash
   cd backend
   go build -o bin/chrono-convoy-server ./cmd/server
   ./bin/chrono-convoy-server
   ```
2. **Compile & Run the Desktop Fat JAR (`client/`)**:
   ```bash
   cd client
   ./gradlew clean fatJar
   java -jar build/libs/chrono-convoy-desktop-1.0.0.jar
   ```

---

## 2. The Convoy & 3D Tactical Viewport

Your convoy consists of three articulated Box2D/3D units travelling along a continuous `CatmullRomSpline` desert railway:
1. **Lead Locomotive (`locomotive.obj`, `42.0 tons`)**: Houses the 100W/130W nuclear-steam reactor, cowcatcher ramming prow, and drive wheels.
2. **Carriage #1 — Artillery Turret Car (`carriage_artillery.obj`)**: Houses the dual-barrel auto-targeting rotary cannon that engages approaching raiders.
3. **Carriage #2 — Armored Cargo & Shield Car (`carriage_cargo.obj`)**: Houses the Thermal Shield Dome emitter and pressurized cargo hold (`60.0 tons` maximum capacity).

### Enemy Raider Archetypes
- **Rammer Buggy (`raider_buggy.obj`)**: High-speed desert interceptors that close in to ram the locomotive or rear carriages, dealing kinetic hull damage and spiking coupler tension.
- **Harpoon & Mortar Skiff (`raider_harpoon_rig.obj`)**: Medium-range heavy rigs that lob high-arcing artillery shells and harpoon tethers to drag down convoy speed.

---

## 3. Operating the Engineer's Control Deck HUD

The bottom third of the viewport displays the diegetic **Engineer's Control Deck HUD** (`convoy_hud_skin.json` / `convoy_hud.atlas`), divided into three panels:

### Left Panel: Reactor Watt Sliders & Thermal Control
Your reactor generates **100W continuous power** (`130W` when Overclocked). Each subsystem slider ranges from `0W` to `60W`, and the `ReactorPowerController` automatically enforces strict watt conservation:
- **Shields Slider (`reactor-shields`)**:
  - Increases shield damage absorption (`0%` at `0W` up to `78%` damage mitigation at `60W`).
  - Prioritize **Shields (`40W–55W`)** when **Rammer Buggies** are on an intercept course or mortar volleys are inbound.
- **Artillery Slider (`reactor-artillery`)**:
  - Scales turret reload speed, projectile muzzle velocity, and turret yaw tracking rate.
  - Prioritize **Artillery (`40W–55W`)** as soon as raiders enter the sector to destroy them before impact.
- **Thrusters Slider (`reactor-thrusters`)**:
  - Scales locomotive tractive force (`0.5x` crawl at `0W` up to `1.75x` full steam at `60W`).
  - Prioritize **Thrusters (`45W–60W`)** on clear straightaways or when escaping a high-threat canyon sector.
- **Core Temp Bar (`core-temp`) & Cooling Rod Button**:
  - Running the reactor near peak capacity or activating **Overclock (`+30W`)** generates `6.5 heat/sec`.
  - If Core Temp reaches **`100.0%`**, the reactor enters **Thermal Lockout** until heat drops below `65.0%`.
  - Click the **Cooling Rod Button** below the Core Temp bar to consume `1` **Reactor Cooling Rod** and immediately drop Core Temp by **`45.0%`**.

### Center Panel: Physics & Braking Telemetry
- **Cargo Tonnage Bar (`cargo-tonnage`, `0–60t`)**: Shows current commodity load in tons.
- **Braking Distance Bar (`braking-distance`, `0–250m`)**: Computes real-time stopping distance $d = \frac{v^2}{2a}$ based on current forward speed $v$ (`m/s`) and total convoy mass (`42t` base locomotive + carriages + cargo tonnage). Heavy cargo loads require earlier braking before sharp spline curves or track switches!

### Right Panel: Track Fork Switcher
Click one of the three track-alignment buttons before reaching a rail junction:
- **Left Branch (`LEFT_BRANCH` — Scrap Canyon Trench)**: Tight curvature, high raider spawn rate, bonus scrap salvage.
- **Main Line (`MAIN_LINE` — Standard Ballast Grade)**: Balanced curvature and threat level.
- **Right Branch (`RIGHT_BRANCH` — Alkali Salt Flats)**: Wide, flat rails allowing maximum thruster speed with minimal raider cover.

---

## 4. Oasis Hub Trading & Arbitrage Guide

Between combat sectors, your convoy docks at one of three **Oasis Market Hubs**, each run by an independent backend Actor (`OasisMarketActor`) that updates prices every `60 seconds` based on player supply and demand:

| Oasis Hub | ID | Biome | Cheap Surplus (Buy Here) | High-Value Shortage (Sell Here) |
| :--- | :--- | :--- | :--- | :--- |
| **Hydro-Haven Aquifer Bastion** | `hydro_haven` | Subterranean Basin | **Purified Water** (`~21–24 Scrip`) | **Reactor Cells** (`~216–235 Scrip`) |
| **Iron-Refinery Slag Foundry** | `iron_refinery` | Volcanic Rust Canyon | **Scrap Alloy** (`~31–34 Scrip`) & **Crude Promethium** (`~72–80 Scrip`) | **Reactor Cooling Rods** (`~144–157 Scrip`) |
| **Aether-Spire Core Relay** | `aether_spire` | Glass Fulgarite Plateau | **Reactor Cells** (`~86–94 Scrip`) & **Reactor Cooling Rods** (`~61–67 Scrip`) | **Crude Promethium** (`~152–166 Scrip`) & **Scrap Alloy** (`~65–71 Scrip`) |

### Golden Triangle Arbitrage Route
1. **Start at `hydro_haven`**: Buy **Purified Water** (`~24 Scrip`) and haul it to **`iron_refinery`** or **`aether_spire`** (`~47–53 Scrip` bid).
2. **At `iron_refinery`**: Buy **Scrap Alloy** (`~34 Scrip`) and **Crude Promethium** (`~80 Scrip`), then haul them to **`aether_spire`** (`~65 Scrip` and `~152 Scrip` bid).
3. **At `aether_spire`**: Buy **Reactor Cells** (`~94 Scrip`) and **Reactor Cooling Rods** (`~67 Scrip`)—keep `2–3` Cooling Rods in your locomotive reserve for combat overclocking, and sell the rest at **`hydro_haven`** (`~216 Scrip` for Reactor Cells) or **`iron_refinery`** (`~144 Scrip` for Cooling Rods).
