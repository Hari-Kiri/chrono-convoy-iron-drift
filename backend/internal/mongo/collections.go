package mongo

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/chrono-convoy/backend/internal/actor"
)

var (
	ErrNoSQLInjectionAttempt = errors.New("nosql defense: identifier contains forbidden operator or control characters")
	ErrBlueprintNotFound     = errors.New("modular carriage blueprint not found")
)

const MaxTelemetrySamplesPerBucket = 120

// CarriageBlueprint represents a modular carriage blueprint document in `carriage_blueprints` (`convoy_config.json`).
type CarriageBlueprint struct {
	BlueprintID         string  `json:"blueprint_id" bson:"blueprint_id"`
	Name                string  `json:"name" bson:"name"`
	SlotCategory        string  `json:"slot_category" bson:"slot_category"` // OFFENSE, DEFENSE, UTILITY
	Tier                int     `json:"tier" bson:"tier"`
	MassTonnes          int     `json:"mass_tonnes" bson:"mass_tonnes"`
	ArmorHP             int     `json:"armor_hp" bson:"armor_hp"`
	BaseWattDraw        int     `json:"base_watt_draw" bson:"base_watt_draw"`
	CargoCapacityBonus  int     `json:"cargo_capacity_bonus" bson:"cargo_capacity_bonus"`
	BaseDPSAtZeroWatts  float64 `json:"base_dps_at_zero_watts" bson:"base_dps_at_zero_watts"`
	MaxDPSAtSixtyWatts  float64 `json:"max_dps_at_sixty_watts" bson:"max_dps_at_sixty_watts"`
	PassiveScrapRefine  float64 `json:"passive_scrap_refine_per_sec" bson:"passive_scrap_refine_per_sec"`
}

// TelemetrySample represents one high-frequency convoy telemetry frame inside a `run_telemetry_buckets` document.
type TelemetrySample struct {
	Tick        int     `json:"t" bson:"t"`
	Watts       int     `json:"w" bson:"w"`
	ReactorHeat float64 `json:"heat" bson:"heat"`
	SpeedMps    float64 `json:"spd" bson:"spd"`
}

// RunTelemetryBucket implements the MongoDB Bucket Pattern (`run_telemetry_buckets`, max 120 samples/bucket).
type RunTelemetryBucket struct {
	RunID                    string            `json:"run_id" bson:"run_id"`
	ConvoyID                 string            `json:"convoy_id" bson:"convoy_id"`
	PlayerID                 string            `json:"player_id" bson:"player_id"`
	BucketStart              time.Time         `json:"bucket_start" bson:"bucket_start"`
	BucketEnd                time.Time         `json:"bucket_end" bson:"bucket_end"`
	SampleCount              int               `json:"sample_count" bson:"sample_count"`
	SumReactorHeat           float64           `json:"sum_reactor_heat" bson:"sum_reactor_heat"`
	MaxReactorHeat           float64           `json:"max_reactor_heat" bson:"max_reactor_heat"`
	CoolingRodsFlushedTotal  int               `json:"cooling_rods_flushed_total" bson:"cooling_rods_flushed_total"`
	Samples                  []TelemetrySample `json:"samples" bson:"samples"`
}

// IndexSpec defines a MongoDB index following the ESR (Equality, Sort, Range) rule.
type IndexSpec struct {
	Collection string         `json:"collection"`
	Name       string         `json:"name"`
	Keys       []IndexKeyItem `json:"keys"`
	Unique     bool           `json:"unique"`
	TTLSeconds int64          `json:"ttl_seconds,omitempty"`
}

type IndexKeyItem struct {
	Field string `json:"field"`
	Order int    `json:"order"` // 1 asc, -1 desc
}

// SanitizeIdentifier enforces OWASP 2025 A05 NoSQL injection defense by rejecting '$' operators or invalid chars.
func SanitizeIdentifier(input string) (string, error) {
	trimmed := strings.TrimSpace(input)
	if trimmed == "" || len(trimmed) > 128 {
		return "", ErrNoSQLInjectionAttempt
	}
	if strings.ContainsAny(trimmed, "$.{}[]\"'\\;\x00") {
		return "", fmt.Errorf("%w: %q", ErrNoSQLInjectionAttempt, input)
	}
	return trimmed, nil
}

// CanonicalCarriageBlueprints returns the 4 modular carriage blueprints from `docs/design/convoy_config.json`.
func CanonicalCarriageBlueprints() []CarriageBlueprint {
	return []CarriageBlueprint{
		{
			BlueprintID:        "gatling_turret_car",
			Name:               "Twin-Vulcan Rotary Carriage",
			SlotCategory:       "OFFENSE",
			Tier:               1,
			MassTonnes:         18,
			ArmorHP:            450,
			BaseWattDraw:       10,
			CargoCapacityBonus: 40,
			BaseDPSAtZeroWatts: 18.0,
			MaxDPSAtSixtyWatts: 95.0,
		},
		{
			BlueprintID:        "shield_dome_car",
			Name:               "Aegis-IV Harmonic Projector Car",
			SlotCategory:       "DEFENSE",
			Tier:               1,
			MassTonnes:         22,
			ArmorHP:            650,
			BaseWattDraw:       15,
			CargoCapacityBonus: 30,
		},
		{
			BlueprintID:        "cargo_smelter_car",
			Name:               "Blast-Furnace Ore Hopper & Smelter",
			SlotCategory:       "UTILITY",
			Tier:               1,
			MassTonnes:         28,
			ArmorHP:            550,
			BaseWattDraw:       10,
			CargoCapacityBonus: 180,
			PassiveScrapRefine: 1.5,
		},
		{
			BlueprintID:        "artillery_mortar_car",
			Name:               "Siege-Howitzer Plasma Mortar Carriage",
			SlotCategory:       "OFFENSE",
			Tier:               2,
			MassTonnes:         26,
			ArmorHP:            500,
			BaseWattDraw:       15,
			CargoCapacityBonus: 25,
			BaseDPSAtZeroWatts: 25.0,
			MaxDPSAtSixtyWatts: 165.0,
		},
	}
}

// RequiredESRIndexes returns the MongoDB ESR + TTL index definitions matching `migrations/002_mongo_collections.js`.
func RequiredESRIndexes() []IndexSpec {
	return []IndexSpec{
		{
			Collection: "carriage_blueprints",
			Name:       "uk_carriage_blueprints_id",
			Keys:       []IndexKeyItem{{Field: "blueprint_id", Order: 1}},
			Unique:     true,
		},
		{
			Collection: "carriage_blueprints",
			Name:       "idx_carriage_blueprints_esr",
			Keys: []IndexKeyItem{
				{Field: "slot_category", Order: 1},
				{Field: "tier", Order: 1},
				{Field: "base_watt_draw", Order: 1},
			},
		},
		{
			Collection: "run_telemetry_buckets",
			Name:       "idx_telemetry_convoy_run_start_esr",
			Keys: []IndexKeyItem{
				{Field: "convoy_id", Order: 1},
				{Field: "run_id", Order: 1},
				{Field: "bucket_start", Order: -1},
			},
		},
		{
			Collection: "run_telemetry_buckets",
			Name:       "idx_telemetry_ttl_30d",
			Keys:       []IndexKeyItem{{Field: "bucket_start", Order: 1}},
			TTLSeconds: 30 * 24 * 3600,
		},
		{
			Collection: "oasis_hub_snapshots",
			Name:       "uk_oasis_hub_tick_esr",
			Keys: []IndexKeyItem{
				{Field: "hub_id", Order: 1},
				{Field: "tick_epoch", Order: -1},
			},
			Unique: true,
		},
		{
			Collection: "oasis_hub_snapshots",
			Name:       "idx_oasis_snapshots_ttl_7d",
			Keys:       []IndexKeyItem{{Field: "updated_at", Order: 1}},
			TTLSeconds: 7 * 24 * 3600,
		},
	}
}

// BuildTelemetrySummaryPipeline constructs an index-covered MongoDB aggregation pipeline ($match -> $project -> $group).
func BuildTelemetrySummaryPipeline(convoyID, runID string) ([]map[string]any, error) {
	safeConvoy, err := SanitizeIdentifier(convoyID)
	if err != nil {
		return nil, err
	}
	safeRun, err := SanitizeIdentifier(runID)
	if err != nil {
		return nil, err
	}
	return []map[string]any{
		{
			"$match": map[string]any{
				"convoy_id": safeConvoy,
				"run_id":    safeRun,
			},
		},
		{
			"$project": map[string]any{
				"_id":                        0,
				"run_id":                     1,
				"convoy_id":                  1,
				"sample_count":               1,
				"sum_reactor_heat":           1,
				"max_reactor_heat":           1,
				"cooling_rods_flushed_total": 1,
			},
		},
		{
			"$group": map[string]any{
				"_id":                "$run_id",
				"total_samples":      map[string]any{"$sum": "$sample_count"},
				"total_heat_sum":     map[string]any{"$sum": "$sum_reactor_heat"},
				"peak_reactor_heat":  map[string]any{"$max": "$max_reactor_heat"},
				"total_rods_flushed": map[string]any{"$sum": "$cooling_rods_flushed_total"},
			},
		},
	}, nil
}

// DocumentStore manages modular carriage blueprints, bucketed run telemetry, and computed Oasis hub snapshots.
type DocumentStore struct {
	mu                sync.RWMutex
	blueprints        map[string]CarriageBlueprint
	telemetryBuckets  []RunTelemetryBucket
	oasisHubSnapshots map[string]actor.HubMarketSnapshot
}

func NewDocumentStore() *DocumentStore {
	ds := &DocumentStore{
		blueprints:        make(map[string]CarriageBlueprint, 4),
		telemetryBuckets:  make([]RunTelemetryBucket, 0, 32),
		oasisHubSnapshots: make(map[string]actor.HubMarketSnapshot, 3),
	}
	for _, bp := range CanonicalCarriageBlueprints() {
		ds.blueprints[bp.BlueprintID] = bp
	}
	return ds
}

func (ds *DocumentStore) ListBlueprints(slotCategory string) ([]CarriageBlueprint, error) {
	var cleanCat string
	if slotCategory != "" {
		c, err := SanitizeIdentifier(slotCategory)
		if err != nil {
			return nil, err
		}
		cleanCat = strings.ToUpper(c)
	}

	ds.mu.RLock()
	defer ds.mu.RUnlock()

	out := make([]CarriageBlueprint, 0, len(ds.blueprints))
	for _, bp := range CanonicalCarriageBlueprints() {
		if cleanCat == "" || bp.SlotCategory == cleanCat {
			out = append(out, bp)
		}
	}
	return out, nil
}

func (ds *DocumentStore) AppendTelemetrySample(runID, convoyID, playerID string, sample TelemetrySample, rodsFlushedDelta int) error {
	if _, err := SanitizeIdentifier(runID); err != nil {
		return err
	}
	if _, err := SanitizeIdentifier(convoyID); err != nil {
		return err
	}

	ds.mu.Lock()
	defer ds.mu.Unlock()

	now := time.Now().UTC()
	n := len(ds.telemetryBuckets)
	if n > 0 {
		last := &ds.telemetryBuckets[n-1]
		if last.RunID == runID && last.ConvoyID == convoyID && last.SampleCount < MaxTelemetrySamplesPerBucket {
			last.Samples = append(last.Samples, sample)
			last.SampleCount++
			last.BucketEnd = now
			last.SumReactorHeat += sample.ReactorHeat
			if sample.ReactorHeat > last.MaxReactorHeat {
				last.MaxReactorHeat = sample.ReactorHeat
			}
			last.CoolingRodsFlushedTotal += rodsFlushedDelta
			return nil
		}
	}

	bucket := RunTelemetryBucket{
		RunID:                   runID,
		ConvoyID:                convoyID,
		PlayerID:                playerID,
		BucketStart:             now,
		BucketEnd:               now,
		SampleCount:             1,
		SumReactorHeat:          sample.ReactorHeat,
		MaxReactorHeat:          sample.ReactorHeat,
		CoolingRodsFlushedTotal: rodsFlushedDelta,
		Samples:                 []TelemetrySample{sample},
	}
	ds.telemetryBuckets = append(ds.telemetryBuckets, bucket)
	return nil
}

func (ds *DocumentStore) GetTelemetryBuckets(convoyID, runID string) ([]RunTelemetryBucket, error) {
	if _, err := SanitizeIdentifier(convoyID); err != nil {
		return nil, err
	}
	if _, err := SanitizeIdentifier(runID); err != nil {
		return nil, err
	}

	ds.mu.RLock()
	defer ds.mu.RUnlock()

	out := make([]RunTelemetryBucket, 0, 4)
	for _, b := range ds.telemetryBuckets {
		if b.ConvoyID == convoyID && b.RunID == runID {
			out = append(out, b)
		}
	}
	return out, nil
}

func (ds *DocumentStore) SaveOasisSnapshot(snap actor.HubMarketSnapshot) {
	ds.mu.Lock()
	ds.oasisHubSnapshots[snap.HubID] = snap
	ds.mu.Unlock()
}
