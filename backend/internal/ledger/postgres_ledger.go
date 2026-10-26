package ledger

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/chrono-convoy/backend/internal/replay"
	_ "github.com/lib/pq"
)

var (
	ErrNotFound              = errors.New("requested player or convoy record not found")
	ErrForbiddenConvoyOwner  = errors.New("abac error: player does not own the specified convoy")
	ErrInsufficientFunds     = errors.New("insufficient iron_scrip or cooling rods in player wallet/bay")
	ErrInsufficientCargo     = errors.New("insufficient commodity quantity in convoy cargo hold")
	ErrCargoCapacityExceeded = errors.New("convoy cargo hold capacity exceeded")
	ErrInvalidLoadoutSpec    = errors.New("invalid convoy loadout specification or watt allocation")
)

// CarriageSlot represents a single modular carriage mounted on a convoy (`convoy_config.json`).
type CarriageSlot struct {
	Slot        int    `json:"slot"`
	BlueprintID string `json:"blueprint_id"`
	WattDraw    int    `json:"watt_draw"`
	CargoCap    int    `json:"cargo_cap"`
}

// CargoItem represents a commodity stack stored in `convoy_cargo_inventory`.
type CargoItem struct {
	CommodityID string    `json:"commodity_id"`
	Quantity    int       `json:"quantity"`
	AvgUnitCost int64     `json:"avg_unit_cost"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// PlayerWallet represents a player's ACID currency balances in `player_wallets`.
type PlayerWallet struct {
	PlayerID   string    `json:"player_id"`
	IronScrip  int64     `json:"iron_scrip"`
	ChronoFlux int64     `json:"chrono_flux"`
	Version    int64     `json:"version"`
	UpdatedAt  time.Time `json:"updated_at"`
}

// ConvoyLoadout represents the complete convoy configuration, reactor budget, wallet, and cargo hold.
type ConvoyLoadout struct {
	ConvoyID          string         `json:"convoy_id"`
	PlayerID          string         `json:"player_id"`
	LocomotiveClass   string         `json:"locomotive_class"`
	MaxReactorWatts   int            `json:"max_reactor_watts"`
	MaxCargoCapacity  int            `json:"max_cargo_capacity"`
	CurrentCargoUnits int            `json:"current_cargo_units"`
	CoolingRodBay     int            `json:"cooling_rod_bay"`
	Carriages         []CarriageSlot `json:"carriages"`
	Version           int64          `json:"version"`
	UpdatedAt         time.Time      `json:"updated_at"`
	Wallet            *PlayerWallet  `json:"wallet,omitempty"`
	CargoHold         []CargoItem    `json:"cargo_hold,omitempty"`
}

// UpdateLoadoutRequest is the input contract for updating a convoy's modular carriages and reactor watt budget.
type UpdateLoadoutRequest struct {
	ConvoyID         string         `json:"convoy_id"`
	PlayerID         string         `json:"player_id"`
	LocomotiveClass  string         `json:"locomotive_class,omitempty"`
	MaxReactorWatts  int            `json:"max_reactor_watts,omitempty"`
	MaxCargoCapacity int            `json:"max_cargo_capacity,omitempty"`
	CoolingRodBay    int            `json:"cooling_rod_bay,omitempty"`
	Carriages        []CarriageSlot `json:"carriages"`
}

// TradeLedgerRequest describes an atomic commodity buy or delivery transaction at an Oasis Hub.
type TradeLedgerRequest struct {
	IdempotencyKey string `json:"idempotency_key"`
	PlayerID       string `json:"player_id"`
	ConvoyID       string `json:"convoy_id"`
	HubID          string `json:"hub_id"`
	CommodityID    string `json:"commodity_id"`
	Action         string `json:"action"` // "BUY" or "DELIVER"
	Quantity       int    `json:"quantity"`
	UnitPriceScrip int64  `json:"unit_price_scrip"`
}

// LedgerEntry represents an immutable committed row in `commodity_ledger_entries`.
type LedgerEntry struct {
	EntryID           int64     `json:"entry_id"`
	IdempotencyKey    string    `json:"idempotency_key"`
	PlayerID          string    `json:"player_id"`
	ConvoyID          string    `json:"convoy_id"`
	HubID             string    `json:"hub_id"`
	CommodityID       string    `json:"commodity_id"`
	EntryType         string    `json:"entry_type"`
	QuantityDelta     int       `json:"quantity_delta"`
	UnitPriceScrip    int64     `json:"unit_price_scrip"`
	ScripDelta        int64     `json:"scrip_delta"`
	ScripBalanceAfter int64     `json:"scrip_balance_after"`
	CargoQtyAfter     int       `json:"cargo_qty_after"`
	PayloadHash       string    `json:"payload_hash"`
	CreatedAt         time.Time `json:"created_at"`
	IdempotentReplay  bool      `json:"idempotent_replay"`
}

// PostgresLedgerRepository manages ACID transactions against PostgreSQL 17.
type PostgresLedgerRepository struct {
	db *sql.DB
}

// NewPostgresLedgerRepository opens and tunes a PostgreSQL connection pool.
func NewPostgresLedgerRepository(dsn string) (*PostgresLedgerRepository, error) {
	if strings.TrimSpace(dsn) == "" {
		dsn = "host=/var/run/postgresql user=box dbname=chrono_convoy sslmode=disable"
	}
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(10)
	db.SetConnMaxLifetime(30 * time.Minute)
	db.SetConnMaxIdleTime(5 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	return &PostgresLedgerRepository{db: db}, nil
}

func (r *PostgresLedgerRepository) Close() error {
	if r.db != nil {
		return r.db.Close()
	}
	return nil
}

func (r *PostgresLedgerRepository) DB() *sql.DB {
	return r.db
}

// GetConvoyLoadout retrieves a convoy loadout, its owner's wallet, and its cargo inventory.
func (r *PostgresLedgerRepository) GetConvoyLoadout(ctx context.Context, callerPlayerID, convoyID string) (*ConvoyLoadout, error) {
	var out ConvoyLoadout
	var carriagesRaw []byte

	err := r.db.QueryRowContext(ctx, `
		SELECT convoy_id, player_id, locomotive_class, max_reactor_watts,
		       max_cargo_capacity, cooling_rod_bay, carriages_json,
		       version, updated_at
		FROM convoy_loadouts
		WHERE convoy_id = $1
	`, convoyID).Scan(
		&out.ConvoyID, &out.PlayerID, &out.LocomotiveClass, &out.MaxReactorWatts,
		&out.MaxCargoCapacity, &out.CoolingRodBay, &carriagesRaw,
		&out.Version, &out.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	// OWASP 2025 A01 ABAC / Anti-IDOR check
	if callerPlayerID != "" && callerPlayerID != out.PlayerID {
		return nil, ErrForbiddenConvoyOwner
	}

	if len(carriagesRaw) > 0 {
		if err := json.Unmarshal(carriagesRaw, &out.Carriages); err != nil {
			return nil, fmt.Errorf("failed to unmarshal carriages_json: %w", err)
		}
	}

	var wallet PlayerWallet
	err = r.db.QueryRowContext(ctx, `
		SELECT player_id, iron_scrip, chrono_flux, version, updated_at
		FROM player_wallets
		WHERE player_id = $1
	`, out.PlayerID).Scan(
		&wallet.PlayerID, &wallet.IronScrip, &wallet.ChronoFlux,
		&wallet.Version, &wallet.UpdatedAt,
	)
	if err == nil {
		out.Wallet = &wallet
	}

	rows, err := r.db.QueryContext(ctx, `
		SELECT commodity_id, quantity, avg_unit_cost, updated_at
		FROM convoy_cargo_inventory
		WHERE convoy_id = $1 AND quantity > 0
		ORDER BY commodity_id ASC
	`, convoyID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out.CargoHold = make([]CargoItem, 0, 5)
	totalCargo := 0
	for rows.Next() {
		var item CargoItem
		if err := rows.Scan(&item.CommodityID, &item.Quantity, &item.AvgUnitCost, &item.UpdatedAt); err != nil {
			return nil, err
		}
		totalCargo += item.Quantity
		out.CargoHold = append(out.CargoHold, item)
	}
	out.CurrentCargoUnits = totalCargo
	return &out, rows.Err()
}

// UpsertConvoyLoadout validates and atomically updates or creates a player's convoy loadout.
func (r *PostgresLedgerRepository) UpsertConvoyLoadout(ctx context.Context, callerPlayerID string, req *UpdateLoadoutRequest) (*ConvoyLoadout, error) {
	if req == nil || strings.TrimSpace(req.ConvoyID) == "" || strings.TrimSpace(req.PlayerID) == "" {
		return nil, ErrInvalidLoadoutSpec
	}
	if callerPlayerID != req.PlayerID {
		return nil, ErrForbiddenConvoyOwner
	}
	maxW := req.MaxReactorWatts
	if maxW <= 0 {
		maxW = 100
	}
	if maxW < 20 || maxW > 500 {
		return nil, fmt.Errorf("%w: max_reactor_watts %d out of range [20, 500]", ErrInvalidLoadoutSpec, maxW)
	}
	if len(req.Carriages) > 6 {
		return nil, fmt.Errorf("%w: max 6 modular carriages allowed", ErrInvalidLoadoutSpec)
	}
	totalCarriageWatts := 0
	for _, c := range req.Carriages {
		if c.WattDraw < 0 || c.CargoCap < 0 || strings.TrimSpace(c.BlueprintID) == "" {
			return nil, fmt.Errorf("%w: invalid carriage slot %+v", ErrInvalidLoadoutSpec, c)
		}
		totalCarriageWatts += c.WattDraw
	}
	if totalCarriageWatts > maxW {
		return nil, fmt.Errorf("%w: carriage idle watt draw (%dW) exceeds max_reactor_watts (%dW)",
			ErrInvalidLoadoutSpec, totalCarriageWatts, maxW)
	}

	locoClass := req.LocomotiveClass
	if locoClass == "" {
		locoClass = "iron_drifter_mk1"
	}
	capUnits := req.MaxCargoCapacity
	if capUnits <= 0 {
		capUnits = 250
	}
	rodBay := req.CoolingRodBay
	if rodBay <= 0 {
		rodBay = 12
	}
	carriagesBytes, err := json.Marshal(req.Carriages)
	if err != nil {
		return nil, err
	}

	tx, err := r.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback() }()

	// Ensure player wallet exists
	_, err = tx.ExecContext(ctx, `
		INSERT INTO player_wallets (player_id, iron_scrip, chrono_flux, version)
		VALUES ($1, 2500, 100, 1)
		ON CONFLICT (player_id) DO NOTHING
	`, req.PlayerID)
	if err != nil {
		return nil, err
	}

	// Check existing convoy ownership if convoy_id exists
	var existingOwner string
	err = tx.QueryRowContext(ctx, `
		SELECT player_id FROM convoy_loadouts WHERE convoy_id = $1 FOR UPDATE
	`, req.ConvoyID).Scan(&existingOwner)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	if err == nil && existingOwner != callerPlayerID {
		return nil, ErrForbiddenConvoyOwner
	}

	_, err = tx.ExecContext(ctx, `
		INSERT INTO convoy_loadouts (
			convoy_id, player_id, locomotive_class, max_reactor_watts,
			max_cargo_capacity, cooling_rod_bay, carriages_json, version, updated_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, 1, NOW())
		ON CONFLICT (convoy_id) DO UPDATE SET
			locomotive_class = EXCLUDED.locomotive_class,
			max_reactor_watts = EXCLUDED.max_reactor_watts,
			max_cargo_capacity = EXCLUDED.max_cargo_capacity,
			cooling_rod_bay = EXCLUDED.cooling_rod_bay,
			carriages_json = EXCLUDED.carriages_json,
			version = convoy_loadouts.version + 1,
			updated_at = NOW()
	`, req.ConvoyID, req.PlayerID, locoClass, maxW, capUnits, rodBay, string(carriagesBytes))
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return r.GetConvoyLoadout(ctx, callerPlayerID, req.ConvoyID)
}

// ExecuteIdempotentTrade executes an ACID commodity purchase or delivery with idempotency key deduplication.
func (r *PostgresLedgerRepository) ExecuteIdempotentTrade(ctx context.Context, callerPlayerID string, req *TradeLedgerRequest) (*LedgerEntry, error) {
	if req == nil || strings.TrimSpace(req.IdempotencyKey) == "" || req.Quantity <= 0 || req.UnitPriceScrip <= 0 {
		return nil, errors.New("invalid trade ledger request parameters")
	}
	if callerPlayerID != req.PlayerID {
		return nil, ErrForbiddenConvoyOwner
	}

	tx, err := r.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback() }()

	// 1. Check idempotency key first inside transaction
	existing, err := findLedgerEntryByIdempotencyKeyTx(ctx, tx, req.IdempotencyKey)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		existing.IdempotentReplay = true
		return existing, nil
	}

	// 2. Lock player wallet row (FOR UPDATE)
	var scripBal int64
	err = tx.QueryRowContext(ctx, `
		SELECT iron_scrip
		FROM player_wallets
		WHERE player_id = $1
		FOR UPDATE
	`, req.PlayerID).Scan(&scripBal)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	// 3. Lock convoy_loadouts row (FOR UPDATE) and verify ownership + capacity
	var ownerID string
	var maxCargoCap, coolingRodBay int
	err = tx.QueryRowContext(ctx, `
		SELECT player_id, max_cargo_capacity, cooling_rod_bay
		FROM convoy_loadouts
		WHERE convoy_id = $1
		FOR UPDATE
	`, req.ConvoyID).Scan(&ownerID, &maxCargoCap, &coolingRodBay)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if ownerID != callerPlayerID {
		return nil, ErrForbiddenConvoyOwner
	}

	// Compute current total cargo units across convoy_cargo_inventory
	var totalCargoUnits int
	err = tx.QueryRowContext(ctx, `
		SELECT COALESCE(SUM(quantity), 0)
		FROM convoy_cargo_inventory
		WHERE convoy_id = $1
	`, req.ConvoyID).Scan(&totalCargoUnits)
	if err != nil {
		return nil, err
	}

	var entryType string
	var qtyDelta int
	var scripDelta int64
	var cargoQtyAfter int

	action := strings.ToUpper(strings.TrimSpace(req.Action))
	switch action {
	case "BUY":
		entryType = "OASIS_BUY"
		qtyDelta = req.Quantity
		scripDelta = -int64(req.Quantity) * req.UnitPriceScrip
		if scripBal+scripDelta < 0 {
			return nil, ErrInsufficientFunds
		}
		if totalCargoUnits+req.Quantity > maxCargoCap {
			return nil, ErrCargoCapacityExceeded
		}

		err = tx.QueryRowContext(ctx, `
			INSERT INTO convoy_cargo_inventory (convoy_id, commodity_id, quantity, avg_unit_cost, updated_at)
			VALUES ($1, $2, $3, $4, NOW())
			ON CONFLICT (convoy_id, commodity_id) DO UPDATE SET
				avg_unit_cost = ((convoy_cargo_inventory.quantity * convoy_cargo_inventory.avg_unit_cost) + ($3 * $4)) / (convoy_cargo_inventory.quantity + $3),
				quantity = convoy_cargo_inventory.quantity + $3,
				updated_at = NOW()
			RETURNING quantity
		`, req.ConvoyID, req.CommodityID, req.Quantity, req.UnitPriceScrip).Scan(&cargoQtyAfter)
		if err != nil {
			return nil, err
		}

		if req.CommodityID == "cooling_rods" {
			_, err = tx.ExecContext(ctx, `
				UPDATE convoy_loadouts
				SET cooling_rod_bay = cooling_rod_bay + $2,
				    version = version + 1,
				    updated_at = NOW()
				WHERE convoy_id = $1
			`, req.ConvoyID, req.Quantity)
			if err != nil {
				return nil, err
			}
		}

	case "DELIVER", "SELL":
		entryType = "OASIS_DELIVER"
		qtyDelta = -req.Quantity
		scripDelta = int64(req.Quantity) * req.UnitPriceScrip

		var currentQty int
		err = tx.QueryRowContext(ctx, `
			SELECT quantity FROM convoy_cargo_inventory
			WHERE convoy_id = $1 AND commodity_id = $2
			FOR UPDATE
		`, req.ConvoyID, req.CommodityID).Scan(&currentQty)
		if errors.Is(err, sql.ErrNoRows) || currentQty < req.Quantity {
			return nil, ErrInsufficientCargo
		}
		if err != nil {
			return nil, err
		}

		cargoQtyAfter = currentQty - req.Quantity
		_, err = tx.ExecContext(ctx, `
			UPDATE convoy_cargo_inventory
			SET quantity = $3, updated_at = NOW()
			WHERE convoy_id = $1 AND commodity_id = $2
		`, req.ConvoyID, req.CommodityID, cargoQtyAfter)
		if err != nil {
			return nil, err
		}

	default:
		return nil, fmt.Errorf("unsupported trade action %q", req.Action)
	}

	newScrip := scripBal + scripDelta

	// Update wallet
	_, err = tx.ExecContext(ctx, `
		UPDATE player_wallets
		SET iron_scrip = $2,
		    version = version + 1,
		    updated_at = NOW()
		WHERE player_id = $1
	`, req.PlayerID, newScrip)
	if err != nil {
		return nil, err
	}

	hashSum := sha256.Sum256([]byte(fmt.Sprintf("%s|%s|%s|%s|%s|%d|%d",
		req.IdempotencyKey, req.PlayerID, req.ConvoyID, req.HubID, req.CommodityID, qtyDelta, req.UnitPriceScrip)))
	payloadHash := hex.EncodeToString(hashSum[:])

	var entry LedgerEntry
	err = tx.QueryRowContext(ctx, `
		INSERT INTO commodity_ledger_entries (
			idempotency_key, player_id, convoy_id, hub_id, commodity_id,
			entry_type, quantity_delta, unit_price_scrip,
			scrip_delta, scrip_balance_after, cargo_qty_after, payload_hash
		) VALUES (
			$1, $2, $3, $4, $5,
			$6, $7, $8,
			$9, $10, $11, $12
		)
		RETURNING entry_id, idempotency_key, player_id, convoy_id, hub_id, commodity_id,
		          entry_type, quantity_delta, unit_price_scrip,
		          scrip_delta, scrip_balance_after, cargo_qty_after, payload_hash, created_at
	`, req.IdempotencyKey, req.PlayerID, req.ConvoyID, req.HubID, req.CommodityID,
		entryType, qtyDelta, req.UnitPriceScrip,
		scripDelta, newScrip, cargoQtyAfter, payloadHash,
	).Scan(
		&entry.EntryID, &entry.IdempotencyKey, &entry.PlayerID, &entry.ConvoyID, &entry.HubID, &entry.CommodityID,
		&entry.EntryType, &entry.QuantityDelta, &entry.UnitPriceScrip,
		&entry.ScripDelta, &entry.ScripBalanceAfter, &entry.CargoQtyAfter, &entry.PayloadHash, &entry.CreatedAt,
	)
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return &entry, nil
}

// RecordVerifiedRunAndSettle atomically persists a verified deterministic convoy run and settles rewards/sinks.
func (r *PostgresLedgerRepository) RecordVerifiedRunAndSettle(ctx context.Context, callerPlayerID string, res *replay.VerifyRunResult, deliveryScripBonus int64) (*LedgerEntry, error) {
	if res == nil || !res.Verified {
		return nil, errors.New("cannot settle unverified convoy run")
	}
	if callerPlayerID != res.PlayerID {
		return nil, ErrForbiddenConvoyOwner
	}

	tx, err := r.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback() }()

	// 1. Check idempotency key first
	existing, err := findLedgerEntryByIdempotencyKeyTx(ctx, tx, res.IdempotencyKey)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		existing.IdempotentReplay = true
		return existing, nil
	}

	// 2. Lock player wallet
	var scripBal int64
	err = tx.QueryRowContext(ctx, `
		SELECT iron_scrip
		FROM player_wallets
		WHERE player_id = $1
		FOR UPDATE
	`, res.PlayerID).Scan(&scripBal)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	// 3. Lock convoy_loadouts and verify ownership + cooling rod bay
	var ownerID string
	var coolingRodBay int
	err = tx.QueryRowContext(ctx, `
		SELECT player_id, cooling_rod_bay
		FROM convoy_loadouts
		WHERE convoy_id = $1
		FOR UPDATE
	`, res.ConvoyID).Scan(&ownerID, &coolingRodBay)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if ownerID != callerPlayerID {
		return nil, ErrForbiddenConvoyOwner
	}
	if coolingRodBay < res.CoolingRodsConsumed {
		return nil, fmt.Errorf("%w: convoy cooling_rod_bay has %d rods, run consumed %d",
			ErrInsufficientFunds, coolingRodBay, res.CoolingRodsConsumed)
	}

	totalScripDelta := res.ScrapEarned + deliveryScripBonus
	newScrip := scripBal + totalScripDelta
	newRodBay := coolingRodBay - res.CoolingRodsConsumed

	_, err = tx.ExecContext(ctx, `
		UPDATE player_wallets
		SET iron_scrip = $2,
		    version = version + 1,
		    updated_at = NOW()
		WHERE player_id = $1
	`, res.PlayerID, newScrip)
	if err != nil {
		return nil, err
	}

	_, err = tx.ExecContext(ctx, `
		UPDATE convoy_loadouts
		SET cooling_rod_bay = $2,
		    version = version + 1,
		    updated_at = NOW()
		WHERE convoy_id = $1
	`, res.ConvoyID, newRodBay)
	if err != nil {
		return nil, err
	}

	// Insert into verified_convoy_runs
	_, err = tx.ExecContext(ctx, `
		INSERT INTO verified_convoy_runs (
			run_id, idempotency_key, player_id, convoy_id,
			origin_hub_id, destination_hub_id, seed,
			action_count, scrap_earned, cooling_rods_consumed,
			distance_traveled_m, final_state_hash, signature_scheme, verified_at
		) VALUES (
			$1, $2, $3, $4,
			$5, $6, $7,
			$8, $9, $10,
			$11, $12, $13, NOW()
		)
		ON CONFLICT (run_id) DO NOTHING
	`, res.RunID, res.IdempotencyKey, res.PlayerID, res.ConvoyID,
		res.OriginHubID, res.DestinationHubID, res.Seed,
		res.ActionCount, res.ScrapEarned, res.CoolingRodsConsumed,
		res.DistanceTraveledM, res.FinalStateHash, res.SignatureScheme)
	if err != nil {
		return nil, err
	}

	// Insert immutable ledger audit row
	var entry LedgerEntry
	err = tx.QueryRowContext(ctx, `
		INSERT INTO commodity_ledger_entries (
			idempotency_key, player_id, convoy_id, hub_id, commodity_id,
			entry_type, quantity_delta, unit_price_scrip,
			scrip_delta, scrip_balance_after, cargo_qty_after, payload_hash
		) VALUES (
			$1, $2, $3, $4, 'cooling_rods',
			'RUN_REWARD', $5, 0,
			$6, $7, $8, $9
		)
		RETURNING entry_id, idempotency_key, player_id, convoy_id, hub_id, commodity_id,
		          entry_type, quantity_delta, unit_price_scrip,
		          scrip_delta, scrip_balance_after, cargo_qty_after, payload_hash, created_at
	`, res.IdempotencyKey, res.PlayerID, res.ConvoyID, res.DestinationHubID,
		-res.CoolingRodsConsumed, totalScripDelta, newScrip, newRodBay, res.FinalStateHash,
	).Scan(
		&entry.EntryID, &entry.IdempotencyKey, &entry.PlayerID, &entry.ConvoyID, &entry.HubID, &entry.CommodityID,
		&entry.EntryType, &entry.QuantityDelta, &entry.UnitPriceScrip,
		&entry.ScripDelta, &entry.ScripBalanceAfter, &entry.CargoQtyAfter, &entry.PayloadHash, &entry.CreatedAt,
	)
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return &entry, nil
}

func findLedgerEntryByIdempotencyKeyTx(ctx context.Context, tx *sql.Tx, idempotencyKey string) (*LedgerEntry, error) {
	var entry LedgerEntry
	err := tx.QueryRowContext(ctx, `
		SELECT entry_id, idempotency_key, player_id, convoy_id, hub_id, commodity_id,
		       entry_type, quantity_delta, unit_price_scrip,
		       scrip_delta, scrip_balance_after, cargo_qty_after, payload_hash, created_at
		FROM commodity_ledger_entries
		WHERE idempotency_key = $1
	`, idempotencyKey).Scan(
		&entry.EntryID, &entry.IdempotencyKey, &entry.PlayerID, &entry.ConvoyID, &entry.HubID, &entry.CommodityID,
		&entry.EntryType, &entry.QuantityDelta, &entry.UnitPriceScrip,
		&entry.ScripDelta, &entry.ScripBalanceAfter, &entry.CargoQtyAfter, &entry.PayloadHash, &entry.CreatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &entry, nil
}
