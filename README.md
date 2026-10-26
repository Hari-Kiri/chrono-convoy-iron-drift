# Chrono-Convoy: Iron Drift

**Genre:** Tactical Armored-Train Action-Strategy & Oasis Arbitrage Simulator  
**Engine & Stack:** LibGDX (Java 21, Box2D, Scene2D, 3D API) · Go (Fiber, Actor Model) · PostgreSQL 17 & MongoDB · Blender 4.3 Geometry Nodes  

---

## Studio Architecture & Monorepo Layout

All seven studio disciplines collaborate inside this canonical monorepo without path collisions:

```text
chrono-convoy-iron-drift/
├── docs/design/       # @Droplet — Parametric GDD, convoy_config.json, Oasis arbitrage & reactor sink models, Monte Carlo sims
├── client/            # @Jack    — LibGDX + Box2D + Scene2D client (Gradle 8.10.2 wrapper, JDK 21)
│   └── assets/ui/     # @Mochi   — Runtime Scene2D skins, 9-patches, and texture atlases consumed by the LibGDX client
├── backend/           # @Patrick — Go/Fiber server, Oasis Hub Trade Actor supervision tree, PostgreSQL ACID ledger & replay verifier
├── assets/
│   ├── 3d/            # @Onigiri — Exported .gltf/.g3db procedural convoy & raider meshes and PBR textures
│   ├── ui/            # @Mochi   — Source diegetic HUD wireframes, SVG/PNG plates, and Scene2D skin assets
│   └── audio/         # @Echo    — Velocity-synced adaptive industrial music stems, cue sheet JSON, and artillery/ramming SFX
├── tools/blender/     # @Onigiri — Headless Blender 4.3 Python (bpy) Geometry Nodes generators & export pipelines
└── qa/                # @Aegis   — Headless LibGDX physics/economy test harnesses, Go concurrency/exploit stress suites, and profiling reports
```

---

## Sprint 1 Ticket Mapping (`Milestone 1: Playable Convoy Combat & Oasis Economy Vertical Slice`)

| Ticket ID | Code | Owner | Deliverable Summary | Directory |
| :--- | :--- | :--- | :--- | :--- |
| `z8rctemh44` | **Milestone 1** | Studio | Sprint 1 MVP: Playable Convoy Combat & Oasis Economy Vertical Slice | `/` |
| `z8rctemh4c` | **PM-01** | PM | Sprint 1 Kickoff, Dependency Graph & Critical Path Tracking | `docs/` |
| `z8rctemh4j` | **DES-01** | @Droplet | Chrono-Convoy Sprint 1 Core Loop & Parametric JSON Schema (`convoy_config.json`) | `docs/design/` |
| `z8rctemh4n` | **DES-02** | @Droplet | Oasis Hub Dynamic Arbitrage & Reactor Cooling Rod Sink Model + Monte Carlo Sim | `docs/design/` |
| `z8rctemh4p` | **CLI-01** | @Jack | Continuous Spline Track & Box2D Multi-Car Convoy Physics | `client/` |
| `z8rctemh4q` | **CLI-02** | @Jack | Reactor Watt Power-Routing Controller & Turret Combat Loop | `client/` |
| `z8rctemh4r` | **BE-01** | @Patrick | Oasis Hub Trade Actor Tree & Deterministic Run Replay Endpoint | `backend/` |
| `z8rctemh4t` | **BE-02** | @Patrick | Convoy Loadout & ACID Commodity Ledger Schema (PostgreSQL) | `backend/` |
| `z8rctemh4u` | **3D-01** | @Onigiri | Procedural Geometry Nodes Locomotive, 2 Carriages & 2 Raider Vehicles | `tools/blender/`, `assets/3d/` |
| `z8rctemh4w` | **UI-01** | @Mochi | Diegetic Engineer Control Deck & Scene2D Skin Specification | `assets/ui/`, `client/assets/ui/` |
| `z8rctemh4x` | **AUD-01** | @Echo | Velocity-Synced Industrial Soundtrack Stems & Artillery/Ramming SFX | `assets/audio/` |
| `z8rctemh4z` | **QA-01** | @Aegis | Headless LibGDX Physics/Economy Test Suite & Go Replay Stress Tests | `qa/` |

---

## Local Toolchain Requirements

- **Java:** OpenJDK 21 (`21.0.12.1`)
- **Gradle:** `8.10.2` (`/usr/local/bin/gradle` and `./client/gradlew`)
- **Go:** `1.27.3` (`linux/amd64`)
- **Blender:** `4.3.2` (headless `blender -b -P ...`)
- **PostgreSQL:** `17.11` (database `chrono_convoy`, local superuser `box`)
