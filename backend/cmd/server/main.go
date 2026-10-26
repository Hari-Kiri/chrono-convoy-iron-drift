package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/chrono-convoy/backend/internal/actor"
	"github.com/chrono-convoy/backend/internal/api"
	"github.com/chrono-convoy/backend/internal/ledger"
	"github.com/chrono-convoy/backend/internal/mongo"
	"github.com/chrono-convoy/backend/internal/replay"
)

func main() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pgDSN := os.Getenv("POSTGRES_DSN")
	if pgDSN == "" {
		pgDSN = "postgres://box@localhost:5432/chrono_convoy?sslmode=disable"
	}

	ledgerRepo, err := ledger.NewPostgresLedgerRepository(pgDSN)
	if err != nil {
		log.Fatalf("failed to initialize PostgreSQL ACID ledger repository: %v", err)
	}
	defer func() { _ = ledgerRepo.Close() }()

	supervisor := actor.NewRegionMarketSupervisor(ctx, 256, 60*time.Second)
	defer supervisor.Shutdown()

	keyRing := replay.NewKeyRing(os.Getenv("CHRONO_KEY_ID"), os.Getenv("CHRONO_HMAC_SECRET"))
	verifier := replay.NewVerifier(keyRing)
	docStore := mongo.NewDocumentStore()

	srv := api.NewServer(supervisor, verifier, ledgerRepo, docStore)

	addr := os.Getenv("PORT")
	if addr == "" {
		addr = ":8080"
	} else if addr[0] != ':' && len(addr) <= 5 {
		addr = ":" + addr
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		cancel()
		_ = srv.App().Shutdown()
	}()

	log.Printf("Chrono-Convoy: Iron Drift v1.0.0 Backend listening on %s", addr)
	if err := srv.App().Listen(addr); err != nil {
		log.Fatalf("server exited: %v", err)
	}
}
