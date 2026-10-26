-- ============================================================================
-- Chrono-Convoy: Iron Drift — Sprint 1 PostgreSQL 17 ACID Commodity Ledger
-- Ticket: [BE-02] Convoy Loadout & ACID Commodity Ledger Schema (z8rctemh4t)
-- Aligned with docs/design/convoy_config.json (DES-01 & DES-02)
-- ============================================================================

BEGIN;

-- 1. Player Wallets (ACID Currency Balances with Optimistic + Row Locking)
CREATE TABLE IF NOT EXISTS player_wallets (
    player_id        VARCHAR(64) PRIMARY KEY,
    iron_scrip       BIGINT      NOT NULL DEFAULT 1000 CHECK (iron_scrip >= 0),
    chrono_flux      BIGINT      NOT NULL DEFAULT 0    CHECK (chrono_flux >= 0),
    version          BIGINT      NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Convoy Loadouts (Locomotive + Modular Carriages + Reactor Watt Budget)
CREATE TABLE IF NOT EXISTS convoy_loadouts (
    convoy_id          VARCHAR(64)  PRIMARY KEY,
    player_id          VARCHAR(64)  NOT NULL REFERENCES player_wallets(player_id) ON DELETE RESTRICT,
    locomotive_class   VARCHAR(64)  NOT NULL DEFAULT 'iron_drifter_mk1',
    max_reactor_watts  INTEGER      NOT NULL DEFAULT 100 CHECK (max_reactor_watts BETWEEN 20 AND 500),
    max_cargo_capacity INTEGER      NOT NULL DEFAULT 200 CHECK (max_cargo_capacity > 0),
    cooling_rod_bay    INTEGER      NOT NULL DEFAULT 10  CHECK (cooling_rod_bay >= 0),
    carriages_json     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    version            BIGINT       NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Covering B-Tree index for fast player convoy lookups without heap fetches
CREATE INDEX IF NOT EXISTS idx_convoy_loadouts_player_covering
    ON convoy_loadouts (player_id, convoy_id)
    INCLUDE (locomotive_class, max_reactor_watts, max_cargo_capacity, cooling_rod_bay, version);

-- GIN index on modular carriage JSONB array for blueprint queries
CREATE INDEX IF NOT EXISTS idx_convoy_loadouts_carriages_gin
    ON convoy_loadouts USING GIN (carriages_json);

-- 3. Convoy Cargo Inventory (ACID Per-Commodity Cargo Holdings)
CREATE TABLE IF NOT EXISTS convoy_cargo_inventory (
    convoy_id        VARCHAR(64) NOT NULL REFERENCES convoy_loadouts(convoy_id) ON DELETE CASCADE,
    commodity_id     VARCHAR(64) NOT NULL,
    quantity         INTEGER     NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    avg_unit_cost    BIGINT      NOT NULL DEFAULT 0 CHECK (avg_unit_cost >= 0),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (convoy_id, commodity_id)
);

-- Covering index for hot inventory reads during trade/run verification
CREATE INDEX IF NOT EXISTS idx_convoy_cargo_covering
    ON convoy_cargo_inventory (convoy_id)
    INCLUDE (commodity_id, quantity, avg_unit_cost);

-- 4. ACID Commodity & Currency Ledger Entries (Append-Only with Idempotency Keys)
CREATE TABLE IF NOT EXISTS commodity_ledger_entries (
    entry_id            BIGSERIAL    PRIMARY KEY,
    idempotency_key     VARCHAR(128) NOT NULL UNIQUE,
    player_id           VARCHAR(64)  NOT NULL REFERENCES player_wallets(player_id),
    convoy_id           VARCHAR(64)  NOT NULL REFERENCES convoy_loadouts(convoy_id),
    hub_id              VARCHAR(64)  NOT NULL,
    commodity_id        VARCHAR(64)  NOT NULL,
    entry_type          VARCHAR(32)  NOT NULL CHECK (entry_type IN ('OASIS_BUY', 'OASIS_SELL', 'OASIS_DELIVER', 'RUN_REWARD', 'COOLING_ROD_SINK', 'LOADOUT_SEED')),
    quantity_delta      INTEGER      NOT NULL,
    unit_price_scrip    BIGINT       NOT NULL CHECK (unit_price_scrip >= 0),
    scrip_delta         BIGINT       NOT NULL,
    scrip_balance_after BIGINT       NOT NULL CHECK (scrip_balance_after >= 0),
    cargo_qty_after     INTEGER      NOT NULL CHECK (cargo_qty_after >= 0),
    payload_hash        VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- B-Tree + Covering index for player/convoy ledger history & audit queries
CREATE INDEX IF NOT EXISTS idx_ledger_player_created
    ON commodity_ledger_entries (player_id, created_at DESC)
    INCLUDE (convoy_id, hub_id, commodity_id, entry_type, quantity_delta, scrip_delta, scrip_balance_after);

CREATE INDEX IF NOT EXISTS idx_ledger_hub_commodity_created
    ON commodity_ledger_entries (hub_id, commodity_id, created_at DESC)
    INCLUDE (quantity_delta, unit_price_scrip);

-- 5. Deterministic Run Replay Verification Records
CREATE TABLE IF NOT EXISTS verified_convoy_runs (
    run_id                  VARCHAR(64)      PRIMARY KEY,
    idempotency_key         VARCHAR(128)     NOT NULL UNIQUE,
    player_id               VARCHAR(64)      NOT NULL REFERENCES player_wallets(player_id),
    convoy_id               VARCHAR(64)      NOT NULL REFERENCES convoy_loadouts(convoy_id),
    origin_hub_id           VARCHAR(64)      NOT NULL,
    destination_hub_id      VARCHAR(64)      NOT NULL,
    seed                    BIGINT           NOT NULL,
    action_count            INTEGER          NOT NULL CHECK (action_count >= 0),
    scrap_earned            BIGINT           NOT NULL CHECK (scrap_earned >= 0),
    cooling_rods_consumed   INTEGER          NOT NULL CHECK (cooling_rods_consumed >= 0),
    distance_traveled_m     DOUBLE PRECISION NOT NULL CHECK (distance_traveled_m >= 0),
    final_state_hash        VARCHAR(64)      NOT NULL,
    signature_scheme        VARCHAR(32)      NOT NULL CHECK (signature_scheme IN ('HMAC-SHA256', 'AES-256-GCM')),
    verified_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_verified_runs_player_convoy
    ON verified_convoy_runs (player_id, convoy_id, verified_at DESC)
    INCLUDE (seed, scrap_earned, final_state_hash);

-- Seed canonical default test players & convoys
INSERT INTO player_wallets (player_id, iron_scrip, chrono_flux, version)
VALUES
    ('player_cmdr_riki', 5000, 100, 1),
    ('player_jack_test', 3500, 50, 1),
    ('player_aegis_qa',  10000, 250, 1)
ON CONFLICT (player_id) DO NOTHING;

INSERT INTO convoy_loadouts (convoy_id, player_id, locomotive_class, max_reactor_watts, max_cargo_capacity, cooling_rod_bay, carriages_json)
VALUES
    ('convoy_iron_drift_01', 'player_cmdr_riki', 'iron_drifter_mk1', 100, 250, 12, '[{"slot":1,"blueprint_id":"gatling_turret_car","watt_draw":10,"cargo_cap":40},{"slot":2,"blueprint_id":"cargo_smelter_car","watt_draw":10,"cargo_cap":210}]'::jsonb),
    ('convoy_jack_01',       'player_jack_test', 'iron_drifter_mk1', 100, 200, 10, '[{"slot":1,"blueprint_id":"artillery_mortar_car","watt_draw":15,"cargo_cap":50},{"slot":2,"blueprint_id":"cargo_smelter_car","watt_draw":10,"cargo_cap":150}]'::jsonb),
    ('convoy_qa_01',         'player_aegis_qa',  'iron_drifter_mk1', 130, 300, 15, '[{"slot":1,"blueprint_id":"shield_dome_car","watt_draw":15,"cargo_cap":60},{"slot":2,"blueprint_id":"cargo_smelter_car","watt_draw":10,"cargo_cap":240}]'::jsonb)
ON CONFLICT (convoy_id) DO UPDATE SET
    locomotive_class = EXCLUDED.locomotive_class,
    carriages_json   = EXCLUDED.carriages_json;

INSERT INTO convoy_cargo_inventory (convoy_id, commodity_id, quantity, avg_unit_cost)
VALUES
    ('convoy_iron_drift_01', 'cooling_rods',     10, 95),
    ('convoy_iron_drift_01', 'scrap_alloy',      25, 55),
    ('convoy_iron_drift_01', 'purified_water',   20, 40),
    ('convoy_iron_drift_01', 'crude_promethium', 10, 120),
    ('convoy_iron_drift_01', 'reactor_cells',     8, 185),
    ('convoy_jack_01',       'cooling_rods',      8, 95),
    ('convoy_jack_01',       'scrap_alloy',      15, 55),
    ('convoy_qa_01',         'cooling_rods',     15, 95),
    ('convoy_qa_01',         'scrap_alloy',      30, 55),
    ('convoy_qa_01',         'reactor_cells',    20, 185)
ON CONFLICT (convoy_id, commodity_id) DO UPDATE SET
    quantity      = EXCLUDED.quantity,
    avg_unit_cost = EXCLUDED.avg_unit_cost;

COMMIT;
