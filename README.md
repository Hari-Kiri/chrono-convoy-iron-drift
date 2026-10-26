# Chrono-Convoy: Iron Drift (`v1.0.0`)

**Genre:** Tactical Armored-Train Action-Strategy & Oasis Arbitrage Simulator  
**Engine & Stack:** LibGDX `1.12.1` (Java 21, LWJGL3 3D API, Box2D, Scene2D) · Go `1.27` (Fiber v2, Actor Model Supervision) · PostgreSQL `17.11` & MongoDB · Blender `4.3.2` Geometry Nodes  

---

## Quick Start (One-Command Local Launch)

To verify PostgreSQL, apply database migrations, start the Go backend on `http://127.0.0.1:8080`, build the desktop Fat JAR (if not already built), and launch the game:

```bash
./run-chrono-convoy.sh
```

- Check backend health and Oasis Hub market state only (without launching the client window):
  ```bash
  ./run-chrono-convoy.sh --check
  ```
- Run a deterministic headless sector + Oasis trading + cryptographic replay verification cycle:
  ```bash
  ./run-chrono-convoy.sh --headless --ticks=300 --seed=42069
  ```

---

## 1. How to Run the Backend in a Local Environment

The authoritative backend (`backend/`) is written in Go using Fiber v2, a bounded-mailbox Actor supervision tree (`RegionMarketSupervisor` managing `hydro_haven`, `iron_refinery`, and `aether_spire`), and a PostgreSQL 17 ACID ledger with an automatic in-memory fallback if PostgreSQL is omitted.

### Prerequisites
- **Go:** `1.22+` (tested with `go1.27.3`)
- **PostgreSQL:** `15+` (tested with `PostgreSQL 17.11`) — *optional if using the built-in in-memory ledger fallback*

### Step 1: Initialize the PostgreSQL Database & Schema
```bash
# Ensure PostgreSQL is running
sudo service postgresql start

# Create the database (if it does not exist yet) and apply the ACID ledger migration
psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'chrono_convoy'" | grep -q 1 || \
  psql -U postgres -c "CREATE DATABASE chrono_convoy;"

psql -U postgres -d chrono_convoy -f backend/migrations/001_init_ledger.sql
```
*(Note: On the studio Linux box, the passwordless superuser role is `box`, so `psql -U box ...` is used by default.)*

### Step 2: Configure Environment Variables (Optional)
| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `POSTGRES_DSN` | `postgres://box@localhost:5432/chrono_convoy?sslmode=disable` | PostgreSQL connection string for the ACID commodity/wallet ledger. |
| `PORT` | `:8080` | HTTP bind port (accepts `:8080` or `8080`). |
| `CHRONO_KEY_ID` | `key-v1-default` | Active key ID for `HMAC-SHA256` & `AES-256-GCM` replay verification. |
| `CHRONO_HMAC_SECRET` | *(32-byte studio secret)* | Hex or raw secret key used to verify signed 60Hz client replay logs. |

### Step 3: Build & Run the Go Backend Server
```bash
cd backend

# Compile the standalone server binary
go build -o bin/chrono-convoy-server ./cmd/server

# Run the server (listens on 0.0.0.0:8080 by default)
POSTGRES_DSN="postgres://postgres:postgres@localhost:5432/chrono_convoy?sslmode=disable" \
  ./bin/chrono-convoy-server
```

### Step 4: Verify Backend Endpoints
```bash
# Health check
curl http://127.0.0.1:8080/healthz

# Full release status (PostgreSQL row counts + live 3-Hub Oasis Market Actor quotes)
curl http://127.0.0.1:8080/api/v1/release/status

# Run the backend unit & concurrency race test suite
cd backend && go test -v ./...
```

---

## 2. How to Compile the Standalone Desktop JAR (`client/`)

The client (`client/`) is a Java 21 LibGDX project bundling both the **LWJGL3 OpenGL 3D desktop backend** and the **headless simulation backend**, along with all 3D `.obj`/`.mtl`/`.glb` vehicle meshes, 2D Scene2D HUD texture atlases (`convoy_hud.atlas`), and 44.1 kHz WAV stems/SFX inside `client/assets/`.

### Prerequisites
- **JDK:** OpenJDK `21` (`java -version` -> `21.x`)
- **Gradle:** Uses the included `./gradlew` (`8.10.2`) wrapper inside `client/`

### Compile & Package the Fat JAR
```bash
cd client

# Run all 16 JUnit 5 client physics, reactor, 3D mesh, audio, and HUD tests + build the Fat JAR
./gradlew clean test fatJar
```
*(You can also run `./gradlew dist`, which is an alias for `fatJar`.)*

### Output Artifact
The self-contained executable Fat JAR (`~22 MB`, including all platform natives and game assets) is generated at:
```text
client/build/libs/chrono-convoy-desktop-1.0.0.jar
```

### Running the Compiled JAR
```bash
# 1. Launch the interactive 1280x720 60FPS 3D Desktop Window (connects to http://127.0.0.1:8080 by default)
java -jar client/build/libs/chrono-convoy-desktop-1.0.0.jar

# 2. Point the desktop client at a custom or remote backend URL
java -jar client/build/libs/chrono-convoy-desktop-1.0.0.jar --backend-url=http://127.0.0.1:8080 --seed=42069

# 3. Run in software-rendered OpenGL mode (for virtual X11/Xvfb desktops without a hardware GPU)
DISPLAY=:2 LIBGL_ALWAYS_SOFTWARE=1 java -jar client/build/libs/chrono-convoy-desktop-1.0.0.jar

# 4. Run a headless 60Hz simulation & backend verification cycle (CI / terminal mode)
java -jar client/build/libs/chrono-convoy-desktop-1.0.0.jar --headless --ticks=300 --seed=42069
```

---

## 3. How to Play the Game

In **Chrono-Convoy: Iron Drift**, you command the **Iron Drifter Mk.I Armored Locomotive** pulling three modular carriages (**Artillery Turret Car**, **Thermal Shield Dome Car**, and **Cargo Smelter Car**) across hazardous desert railway sectors between three regional **Oasis Market Hubs**:
1. **Hydro-Haven Aquifer Bastion** (`hydro_haven` — surplus Purified Water, high demand for Reactor Cells)
2. **Iron-Refinery Slag Foundry** (`iron_refinery` — surplus Scrap Alloy & Crude Promethium, high demand for Cooling Rods)
3. **Aether-Spire Core Relay** (`aether_spire` — surplus Reactor Cells & Cooling Rods, high demand for Scrap Alloy)

### Core Gameplay Loop
1. **Oasis Market Arbitrage & Outfitting (`OasisHubScreen`)**:
   - Inspect live bid/ask Scrip spreads across the 5 core commodities (**Purified Water**, **Crude Promethium**, **Scrap Alloy**, **Reactor Cells**, and **Reactor Cooling Rods**).
   - Buy low-price surplus commodities at your departure Oasis Hub, stock up on **Reactor Cooling Rods**, and upgrade carriage slots (`gatling_turret_car`, `plasma_lancer_car`, `shield_dome_car`, `cargo_smelter_car`).
   - Every ton of cargo increases convoy mass, which directly increases your **Braking Distance** (`d = v² / 2a`) and coupler tension on curves.
2. **Tactical 100W / 130W Reactor Power Routing (`EngineerControlDeckHud`)**:
   - Your nuclear-steam reactor generates **100W continuous base power** (expandable to **130W** during Overclock).
   - Use the **Engineer's Control Deck HUD** at the bottom of the screen to dynamically route watts between three subsystems (`0W–60W` each):
     - **Shields (`Blue Slider`)**: Absorbs incoming mortar fire and raider ramming kinetic impact.
     - **Artillery (`Orange Slider`)**: Controls turret fire rate, projectile velocity, and targeting tracking speed against **Raider Rammer Buggies** and **Harpoon/Mortar Skiffs**.
     - **Thrusters (`Green Slider`)**: Controls locomotive tractive force and cruising speed along the desert spline.
3. **Thermal Management & Cooling Rod Sinks**:
   - Allocating high wattage or engaging **Overclock (`+30W` for `20s`)** builds **Core Heat (`0%–100%`)**.
   - If Core Heat reaches `100%`, the reactor triggers an **Emergency Thermal Lockout**, cutting subsystem power until heat vents below `65%`.
   - Flush a consumable **Reactor Cooling Rod** (`-45%` instant heat drop) to sustain high-wattage combat during raider ambushes.
4. **Spline Track Fork Switching**:
   - Ahead of rail junctions, switch the track alignment between:
     - **Left Branch (`Scrap Canyon Trench`)**: High raider ambush density, higher scrap/salvage yield.
     - **Main Line (`Standard Ballast Grade`)**: Balanced speed and threat profile.
     - **Right Branch (`Alkali Salt Flats`)**: Low threat, high-speed bypass route.
5. **Cryptographic Run Verification**:
   - Every watt change, track switch, cooling rod flush, and combat event is recorded at 60Hz (`dt = 1/60s`) into a signed replay chain and verified by the Go backend (`POST /api/v1/convoy/run/verify`) using `HMAC-SHA256` and `AES-256-GCM` before committing Scrip profits to the PostgreSQL ledger.

### Controls & HUD Reference

| Control / Widget | Action | Effect in Simulation |
| :--- | :--- | :--- |
| **Shields Watt Slider** *(Bottom-Left, Top)* | Drag slider (`0W–60W`) | Routes reactor watts to the Thermal Shield Dome; clamps total watts to `<= 100W` (`130W` overclocked). |
| **Artillery Watt Slider** *(Bottom-Left, Middle)* | Drag slider (`0W–60W`) | Increases artillery turret fire cadence and turret yaw tracking speed. |
| **Thrusters Watt Slider** *(Bottom-Left, Bottom)* | Drag slider (`0W–60W`) | Adjusts locomotive tractive thrust multiplier (`0.5x–1.75x`) along the spline track. |
| **Core Temp Gauge** *(Below Sliders)* | Readout (`0%–100%`) | Displays reactor thermal load; turns critical above `80%` and locks out at `100%`. |
| **Flush Cooling Rod Button** *(Below Core Temp)* | Click button | Consumes `1` Cooling Rod from inventory to immediately vent `45%` reactor heat. |
| **Cargo Tonnage & Braking Bars** *(Bottom-Center)* | Readout (`0–60t` / `0–250m`) | Real-time cargo mass and instantaneous stopping distance (`v² / 2a`). |
| **Left / Main / Right Branch Buttons** *(Bottom-Right)* | Click track switch button | Switches the upcoming rail spline fork (`LEFT_BRANCH`, `MAIN_LINE`, `RIGHT_BRANCH`) and triggers rail-switch audio. |

*(For a full deep-dive into controls, economy formulas, and tactical tips, see [`docs/HOW_TO_PLAY.md`](docs/HOW_TO_PLAY.md).)*

---

## 4. Studio Architecture & Monorepo Layout

All seven studio disciplines collaborate inside this canonical monorepo without path collisions:

```text
chrono-convoy-iron-drift/
├── run-chrono-convoy.sh     # One-command PostgreSQL + Go backend + LibGDX desktop launcher
├── RELEASE_NOTES_v1.0.0.md  # Official v1.0.0 Gold Master release notes & verification matrix
├── docs/
│   ├── HOW_TO_PLAY.md       # Complete player manual, controls, Oasis arbitrage guide & tips
│   └── design/              # @Droplet — Parametric GDD, convoy_config.json, Oasis Monte Carlo sims
├── client/                  # @Jack    — LibGDX + LWJGL3 3D + Box2D + Scene2D client (JDK 21)
│   └── assets/              # Staged runtime 3D (.obj/.mtl/.glb), 2D (convoy_hud.atlas), audio (.wav), & config
├── backend/                 # @Patrick — Go/Fiber server, Oasis Hub Actor tree, PostgreSQL ACID ledger & replay verifier
├── assets/
│   ├── 3d/                  # @Onigiri — Source & exported .glb/.obj/.mtl procedural convoy & raider meshes + Cycles render
│   ├── ui/                  # @Mochi   — Source diegetic HUD wireframes, Scene2D skin JSON, 9-patches & texture atlases
│   └── audio/               # @Echo    — 4 velocity-synced adaptive music stems, cue sheet JSON, and 5 combat SFX WAVs
├── tools/blender/           # @Onigiri — Headless Blender 4.3 Python (bpy) Geometry Nodes generators & Cycles renderer
└── qa/                      # @Aegis   — 31 JUnit 5 headless LibGDX/Box2D tests + 13 Go replay stress & live E2E tests
```

---

## 5. Automated QA & Verification Commands (`qa/`)

```bash
# Run all 31 JUnit 5 headless LibGDX physics, economy, and release readiness tests
cd qa && ./gradlew test

# Run all 13 Go replay stress, fuzz, AEAD contract, and live PostgreSQL E2E tests
cd qa && go test -v -race ./...
```
