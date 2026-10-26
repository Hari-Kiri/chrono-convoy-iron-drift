package mongo

import (
	"errors"
	"testing"
)

func TestDocumentStore_BlueprintsBucketPatternAndNoSQLDefense(t *testing.T) {
	ds := NewDocumentStore()

	bps, err := ds.ListBlueprints("")
	if err != nil || len(bps) != 4 {
		t.Fatalf("expected 4 canonical carriage blueprints, got %d, err=%v", len(bps), err)
	}

	offenseBps, err := ds.ListBlueprints("OFFENSE")
	if err != nil || len(offenseBps) != 2 {
		t.Fatalf("expected 2 OFFENSE carriage blueprints, got %d, err=%v", len(offenseBps), err)
	}

	// Verify OWASP 2025 A05 NoSQL injection rejection
	if _, err := ds.ListBlueprints(`{"$ne": null}`); !errors.Is(err, ErrNoSQLInjectionAttempt) {
		t.Fatalf("expected ErrNoSQLInjectionAttempt on NoSQL operator payload, got %v", err)
	}
	if _, err := BuildTelemetrySummaryPipeline("convoy_01", `{"$gt": ""}`); !errors.Is(err, ErrNoSQLInjectionAttempt) {
		t.Fatalf("expected ErrNoSQLInjectionAttempt on pipeline injection, got %v", err)
	}

	// Verify Bucket Pattern splits after MaxTelemetrySamplesPerBucket (120 samples)
	for i := 0; i < 150; i++ {
		err := ds.AppendTelemetrySample("run_bucket_test", "convoy_iron_drift_01", "player_cmdr_riki", TelemetrySample{
			Tick:        i * 10,
			Watts:       100,
			ReactorHeat: 45.0,
			SpeedMps:    28.0,
		}, 0)
		if err != nil {
			t.Fatalf("AppendTelemetrySample failed at %d: %v", i, err)
		}
	}

	buckets, err := ds.GetTelemetryBuckets("convoy_iron_drift_01", "run_bucket_test")
	if err != nil {
		t.Fatalf("GetTelemetryBuckets failed: %v", err)
	}
	if len(buckets) != 2 {
		t.Fatalf("expected 2 telemetry buckets for 150 samples (max 120/bucket), got %d", len(buckets))
	}
	if buckets[0].SampleCount != 120 || buckets[1].SampleCount != 30 {
		t.Fatalf("unexpected bucket sample counts: %d and %d", buckets[0].SampleCount, buckets[1].SampleCount)
	}

	// Verify ESR index definitions and aggregation pipeline stages
	indexes := RequiredESRIndexes()
	if len(indexes) != 6 {
		t.Fatalf("expected 6 ESR/TTL index specs, got %d", len(indexes))
	}
	pipeline, err := BuildTelemetrySummaryPipeline("convoy_iron_drift_01", "run_bucket_test")
	if err != nil || len(pipeline) != 3 {
		t.Fatalf("expected 3-stage aggregation pipeline ($match -> $project -> $group), got %+v, err=%v", pipeline, err)
	}
}
