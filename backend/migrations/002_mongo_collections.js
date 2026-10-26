// ============================================================================
// Chrono-Convoy: Iron Drift — Sprint 1 MongoDB Collections & Index Strategy
// Ticket: [BE-02] Convoy Loadout & ACID Commodity Ledger Schema (z8rctemh4t)
// ============================================================================
// Patterns Applied:
//   1. Document Schema Validation: Modular Carriage Blueprints (`carriage_blueprints`)
//   2. Bucket Pattern (IoT/Time-Series): Run Telemetry (`run_telemetry_buckets`, max 120 ticks/bucket)
//   3. Computed Pattern: Oasis Hub Market State & Arbitrage History (`oasis_hub_snapshots`)
//   4. ESR Compound Indexes (Equality -> Sort -> Range) + TTL Expiration
// ============================================================================

const dbName = "chrono_convoy";
const targetDb = db.getSiblingDB(dbName);

// 1. Modular Carriage Blueprints Collection
targetDb.createCollection("carriage_blueprints", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["blueprint_id", "name", "carriage_class", "tier", "watt_draw", "cargo_capacity", "armor_rating", "turret_mounts"],
      properties: {
        blueprint_id:   { bsonType: "string" },
        name:           { bsonType: "string" },
        carriage_class: { enum: ["ARTILLERY", "CARGO_VAULT", "REACTOR_AUX", "SHIELD_PYLON"] },
        tier:           { bsonType: "int", minimum: 1, maximum: 5 },
        watt_draw:      { bsonType: "int", minimum: 0, maximum: 150 },
        cargo_capacity: { bsonType: "int", minimum: 0, maximum: 500 },
        armor_rating:   { bsonType: "int", minimum: 10, maximum: 2000 },
        turret_mounts:  { bsonType: "int", minimum: 0, maximum: 4 },
        mass_tonnes:    { bsonType: "double", minimum: 1.0 }
      }
    }
  }
});

// ESR Index: Equality (carriage_class, tier), Sort/Range (watt_draw, cargo_capacity)
targetDb.carriage_blueprints.createIndex(
  { carriage_class: 1, tier: 1, watt_draw: 1, cargo_capacity: -1 },
  { name: "idx_esr_carriage_class_tier_watt" }
);
targetDb.carriage_blueprints.createIndex(
  { blueprint_id: 1 },
  { unique: true, name: "uniq_blueprint_id" }
);

// 2. Run Telemetry Buckets Collection (Bucket Pattern: up to 120 ticks per document)
targetDb.createCollection("run_telemetry_buckets", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["bucket_id", "run_id", "convoy_id", "player_id", "bucket_start_tick", "bucket_end_tick", "sample_count", "samples", "expires_at"],
      properties: {
        bucket_id:         { bsonType: "string" },
        run_id:            { bsonType: "string" },
        convoy_id:         { bsonType: "string" },
        player_id:         { bsonType: "string" },
        bucket_start_tick: { bsonType: "int", minimum: 0 },
        bucket_end_tick:   { bsonType: "int", minimum: 0 },
        sample_count:      { bsonType: "int", minimum: 1, maximum: 120 },
        samples:           { bsonType: "array", maxItems: 120 },
        computed_metrics:  { bsonType: "object" },
        expires_at:        { bsonType: "date" }
      }
    }
  }
});

// ESR Index for hot run telemetry queries: Equality (convoy_id, run_id), Sort (bucket_start_tick ASC)
targetDb.run_telemetry_buckets.createIndex(
  { convoy_id: 1, run_id: 1, bucket_start_tick: 1 },
  { name: "idx_esr_telemetry_convoy_run_tick" }
);

// TTL Index for automatic telemetry retention purging (30 days)
targetDb.run_telemetry_buckets.createIndex(
  { expires_at: 1 },
  { expireAfterSeconds: 0, name: "idx_ttl_telemetry_expires_at" }
);

// 3. Oasis Hub Snapshots Collection (Computed Pattern: pre-computed 60s arbitrage & supply/demand metrics)
targetDb.createCollection("oasis_hub_snapshots", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["hub_id", "tick_epoch", "commodities", "computed_arbitrage_spread", "updated_at"],
      properties: {
        hub_id:                    { enum: ["oasis_alpha", "oasis_beta", "oasis_gamma"] },
        tick_epoch:                { bsonType: "long" },
        commodities:               { bsonType: "object" },
        computed_arbitrage_spread: { bsonType: "double" },
        updated_at:                { bsonType: "date" }
      }
    }
  }
});

// ESR Index for Oasis Hub hot queries: Equality (hub_id), Sort (tick_epoch DESC), Range (computed_arbitrage_spread)
targetDb.oasis_hub_snapshots.createIndex(
  { hub_id: 1, tick_epoch: -1, computed_arbitrage_spread: -1 },
  { name: "idx_esr_oasis_hub_tick_spread" }
);
