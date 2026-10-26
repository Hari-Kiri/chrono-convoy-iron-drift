package api

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/chrono-convoy/backend/internal/actor"
	"github.com/chrono-convoy/backend/internal/ledger"
	"github.com/chrono-convoy/backend/internal/mongo"
	"github.com/chrono-convoy/backend/internal/replay"
	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/utils"
)

const localPlayerIDKey = "authenticated_player_id"

// Server encapsulates the Go Fiber v2 app, Regional Oasis Actor Supervisor, Replay Verifier, PostgreSQL Ledger, and Mongo DocumentStore.
type Server struct {
	app        *fiber.App
	supervisor *actor.RegionMarketSupervisor
	verifier   *replay.Verifier
	ledgerRepo *ledger.PostgresLedgerRepository
	docStore   *mongo.DocumentStore
}

// MarketTradeHTTPRequest represents the request payload for POST /api/v1/oasis/market (/trade).
type MarketTradeHTTPRequest struct {
	IdempotencyKey string `json:"idempotency_key"`
	PlayerID       string `json:"player_id"`
	ConvoyID       string `json:"convoy_id"`
	HubID          string `json:"hub_id"`
	CommodityID    string `json:"commodity_id"`
	Action         string `json:"action"` // "BUY" or "DELIVER"
	Quantity       int    `json:"quantity"`
}

// NewServer constructs and wires the Fiber v2 application with zero-allocation safe handlers and OWASP 2025 middleware.
func NewServer(
	supervisor *actor.RegionMarketSupervisor,
	verifier *replay.Verifier,
	ledgerRepo *ledger.PostgresLedgerRepository,
	docStore *mongo.DocumentStore,
) *Server {
	app := fiber.New(fiber.Config{
		AppName:               "Chrono-Convoy: Iron Drift Backend v1.0",
		DisableStartupMessage: true,
		ReadTimeout:           10 * time.Second,
		WriteTimeout:          10 * time.Second,
		IdleTimeout:           30 * time.Second,
		BodyLimit:             1 * 1024 * 1024, // 1 MB payload cap (OWASP DoS protection)
	})

	s := &Server{
		app:        app,
		supervisor: supervisor,
		verifier:   verifier,
		ledgerRepo: ledgerRepo,
		docStore:   docStore,
	}

	s.registerMiddleware()
	s.registerRoutes()
	return s
}

func (s *Server) App() *fiber.App {
	return s.app
}

func (s *Server) registerMiddleware() {
	// Zero-allocation security headers + ABAC subject extraction middleware
	s.app.Use(func(c *fiber.Ctx) error {
		c.Set("X-Content-Type-Options", "nosniff")
		c.Set("X-Frame-Options", "DENY")
		c.Set("Cache-Control", "no-store")

		// IMPORTANT: Copy strings from fasthttp buffer via utils.CopyString before storing or passing across goroutines
		playerID := utils.CopyString(strings.TrimSpace(c.Get("X-Player-ID")))
		if playerID == "" {
			authHeader := utils.CopyString(strings.TrimSpace(c.Get("Authorization")))
			if strings.HasPrefix(authHeader, "Bearer ") {
				playerID = utils.CopyString(strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer ")))
			}
		}
		c.Locals(localPlayerIDKey, playerID)
		return c.Next()
	})
}

func (s *Server) registerRoutes() {
	s.app.Get("/healthz", s.handleHealthz)

	v1 := s.app.Group("/api/v1")

	// [BE-01] & [BE-02]: Convoy Loadout & ACID Wallet/Cargo Endpoints
	v1.Get("/convoy/loadout", s.handleGetConvoyLoadout)
	v1.Get("/convoy/loadout/:convoy_id", s.handleGetConvoyLoadout)
	v1.Post("/convoy/loadout", s.handleUpsertConvoyLoadout)
	v1.Put("/convoy/loadout", s.handleUpsertConvoyLoadout)

	// [BE-01]: Regional Oasis Hub Market Actor Tree Endpoints
	v1.Get("/oasis/market", s.handleGetOasisMarket)
	v1.Get("/oasis/market/:hub_id", s.handleGetOasisMarket)
	v1.Post("/oasis/market", s.handlePostOasisMarketTrade)
	v1.Post("/oasis/market/trade", s.handlePostOasisMarketTrade)

	// [BE-01]: Deterministic Seed + Action-Log Replay Verification Endpoints
	// Supporting both `/api/v1/convoy/run/verify` (Taro kickoff spec) and `/api/v1/run/verify` (ClickUp BE-01 spec)
	v1.Post("/convoy/run/verify", s.handleVerifyConvoyRun)
	v1.Post("/run/verify", s.handleVerifyConvoyRun)

	// [BE-02]: Modular Carriage Blueprints Catalog (MongoDB schema)
	v1.Get("/blueprints/carriages", s.handleGetCarriageBlueprints)

	// [Sprint 2 v1.0.0 Release]: Unified Release Status, PostgreSQL Ledger & Oasis Market Health
	v1.Get("/release/status", s.handleGetReleaseStatus)
}

func callerPlayerID(c *fiber.Ctx) string {
	val, _ := c.Locals(localPlayerIDKey).(string)
	return val
}

func (s *Server) handleHealthz(c *fiber.Ctx) error {
	ctx, cancel := context.WithTimeout(c.UserContext(), 2*time.Second)
	defer cancel()

	snaps, err := s.supervisor.GetAllHubSnapshots(ctx)
	if err != nil {
		return c.Status(fiber.StatusServiceUnavailable).JSON(fiber.Map{
			"status": "degraded",
			"error":  err.Error(),
		})
	}
	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"status":           "ok",
		"service":          "chrono-convoy-iron-drift-backend",
		"active_oasis_hubs": len(snaps),
	})
}

func (s *Server) handleGetConvoyLoadout(c *fiber.Ctx) error {
	convoyID := utils.CopyString(strings.TrimSpace(c.Params("convoy_id")))
	if convoyID == "" {
		convoyID = utils.CopyString(strings.TrimSpace(c.Query("convoy_id")))
	}
	if convoyID == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "convoy_id is required via path or query parameter",
		})
	}

	callerID := callerPlayerID(c)
	if callerID == "" {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing X-Player-ID or Bearer authentication header",
		})
	}

	ctx, cancel := context.WithTimeout(c.UserContext(), 3*time.Second)
	defer cancel()

	loadout, err := s.ledgerRepo.GetConvoyLoadout(ctx, callerID, convoyID)
	if err != nil {
		switch {
		case errors.Is(err, ledger.ErrForbiddenConvoyOwner):
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, ledger.ErrNotFound):
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
		default:
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
		}
	}
	return c.Status(fiber.StatusOK).JSON(loadout)
}

func (s *Server) handleUpsertConvoyLoadout(c *fiber.Ctx) error {
	callerID := callerPlayerID(c)
	if callerID == "" {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing X-Player-ID or Bearer authentication header",
		})
	}

	var req ledger.UpdateLoadoutRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid JSON payload: " + err.Error(),
		})
	}
	if req.PlayerID == "" {
		req.PlayerID = callerID
	}

	ctx, cancel := context.WithTimeout(c.UserContext(), 3*time.Second)
	defer cancel()

	updated, err := s.ledgerRepo.UpsertConvoyLoadout(ctx, callerID, &req)
	if err != nil {
		switch {
		case errors.Is(err, ledger.ErrForbiddenConvoyOwner):
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, ledger.ErrInvalidLoadoutSpec):
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
		default:
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
		}
	}
	return c.Status(fiber.StatusOK).JSON(updated)
}

func (s *Server) handleGetOasisMarket(c *fiber.Ctx) error {
	hubID := utils.CopyString(strings.TrimSpace(c.Params("hub_id")))
	if hubID == "" {
		hubID = utils.CopyString(strings.TrimSpace(c.Query("hub_id")))
	}

	ctx, cancel := context.WithTimeout(c.UserContext(), 2*time.Second)
	defer cancel()

	if hubID != "" {
		snap, err := s.supervisor.GetHubSnapshot(ctx, hubID)
		if err != nil {
			if errors.Is(err, actor.ErrUnknownHub) {
				return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
			}
			return c.Status(fiber.StatusServiceUnavailable).JSON(fiber.Map{"error": err.Error()})
		}
		s.docStore.SaveOasisSnapshot(snap)
		return c.Status(fiber.StatusOK).JSON(snap)
	}

	snaps, err := s.supervisor.GetAllHubSnapshots(ctx)
	if err != nil {
		return c.Status(fiber.StatusServiceUnavailable).JSON(fiber.Map{"error": err.Error()})
	}
	for _, snap := range snaps {
		s.docStore.SaveOasisSnapshot(snap)
	}
	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"hubs":  snaps,
		"count": len(snaps),
	})
}

func (s *Server) handlePostOasisMarketTrade(c *fiber.Ctx) error {
	callerID := callerPlayerID(c)
	if callerID == "" {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing X-Player-ID or Bearer authentication header",
		})
	}

	var req MarketTradeHTTPRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid JSON body: " + err.Error(),
		})
	}
	if req.PlayerID == "" {
		req.PlayerID = callerID
	}
	if callerID != req.PlayerID {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
			"error": ledger.ErrForbiddenConvoyOwner.Error(),
		})
	}
	if strings.TrimSpace(req.IdempotencyKey) == "" || strings.TrimSpace(req.ConvoyID) == "" ||
		strings.TrimSpace(req.HubID) == "" || strings.TrimSpace(req.CommodityID) == "" || req.Quantity <= 0 {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "idempotency_key, convoy_id, hub_id, commodity_id, and quantity > 0 are required",
		})
	}

	ctx, cancel := context.WithTimeout(c.UserContext(), 4*time.Second)
	defer cancel()

	// 1. Query current Oasis Hub quote via actor message passing
	snap, err := s.supervisor.GetHubSnapshot(ctx, req.HubID)
	if err != nil {
		if errors.Is(err, actor.ErrUnknownHub) {
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
		}
		return c.Status(fiber.StatusServiceUnavailable).JSON(fiber.Map{"error": err.Error()})
	}
	canonComm, err := actor.NormalizeCommodityID(req.CommodityID)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}
	quote := snap.Commodities[canonComm]

	var tradeAction actor.TradeActionType
	var unitPrice int64
	switch strings.ToUpper(strings.TrimSpace(req.Action)) {
	case "BUY":
		tradeAction = actor.TradeActionBuy
		unitPrice = quote.AskPriceScrip
	case "DELIVER", "SELL":
		tradeAction = actor.TradeActionDeliver
		unitPrice = quote.BidPriceScrip
	default:
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "action must be BUY or DELIVER"})
	}

	// 2. Execute ACID PostgreSQL ledger entry with idempotency key deduplication first
	ledgerEntry, err := s.ledgerRepo.ExecuteIdempotentTrade(ctx, callerID, &ledger.TradeLedgerRequest{
		IdempotencyKey: req.IdempotencyKey,
		PlayerID:       req.PlayerID,
		ConvoyID:       req.ConvoyID,
		HubID:          snap.HubID,
		CommodityID:    canonComm,
		Action:         string(tradeAction),
		Quantity:       req.Quantity,
		UnitPriceScrip: unitPrice,
	})
	if err != nil {
		switch {
		case errors.Is(err, ledger.ErrForbiddenConvoyOwner):
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, ledger.ErrInsufficientFunds),
			errors.Is(err, ledger.ErrInsufficientCargo),
			errors.Is(err, ledger.ErrCargoCapacityExceeded):
			return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, ledger.ErrNotFound):
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
		default:
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
		}
	}

	// 3. Only mutate actor supply/demand if this was NOT a duplicate idempotent replay!
	var updatedQuote actor.CommodityQuote = quote
	if !ledgerEntry.IdempotentReplay {
		tradeRes, actorErr := s.supervisor.ExecuteTrade(ctx, snap.HubID, canonComm, tradeAction, req.Quantity)
		if actorErr == nil {
			updatedQuote = tradeRes.UpdatedQuote
		}
	}

	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"ledger_entry":  ledgerEntry,
		"updated_quote": updatedQuote,
	})
}

func (s *Server) handleVerifyConvoyRun(c *fiber.Ctx) error {
	callerID := callerPlayerID(c)
	if callerID == "" {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing X-Player-ID or Bearer authentication header",
		})
	}

	var req replay.VerifyRunRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid JSON payload: " + err.Error(),
		})
	}

	// 1. Run deterministic seed + action-log replay & cryptographic envelope verification
	verifyRes, err := s.verifier.VerifyAndReplay(callerID, &req)
	if err != nil {
		switch {
		case errors.Is(err, replay.ErrForbiddenIDOR):
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, replay.ErrCryptoVerificationFailed),
			errors.Is(err, replay.ErrTamperedReplayState),
			errors.Is(err, replay.ErrWattBudgetExceeded),
			errors.Is(err, replay.ErrNonMonotonicTick),
			errors.Is(err, replay.ErrThermalMeltdownOrRodShortage):
			return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{
				"verified": false,
				"error":    err.Error(),
			})
		default:
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
				"verified": false,
				"error":    err.Error(),
			})
		}
	}

	ctx, cancel := context.WithTimeout(c.UserContext(), 4*time.Second)
	defer cancel()

	// 2. Calculate delivery payout at DestinationHubID if cargo was delivered during the run
	var deliveryBonus int64
	if verifyRes.DestinationHubID != "" && len(verifyRes.DeliveredCargo) > 0 {
		snap, snapErr := s.supervisor.GetHubSnapshot(ctx, verifyRes.DestinationHubID)
		if snapErr == nil {
			for _, item := range verifyRes.DeliveredCargo {
				if canonComm, err := actor.NormalizeCommodityID(item.CommodityID); err == nil {
					q := snap.Commodities[canonComm]
					deliveryBonus += int64(item.Quantity) * q.BidPriceScrip
				}
			}
		}
	}

	// 3. Persist verified run & settle ACID rewards/cooling-rod sink in PostgreSQL with idempotency protection
	ledgerEntry, err := s.ledgerRepo.RecordVerifiedRunAndSettle(ctx, callerID, verifyRes, deliveryBonus)
	if err != nil {
		switch {
		case errors.Is(err, ledger.ErrForbiddenConvoyOwner):
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, ledger.ErrInsufficientFunds):
			return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"error": err.Error()})
		case errors.Is(err, ledger.ErrNotFound):
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
		default:
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
		}
	}

	// 4. If first-time settlement, update OasisMarketActor supply/demand & append telemetry bucket sample
	if !ledgerEntry.IdempotentReplay {
		if verifyRes.DestinationHubID != "" {
			for _, item := range verifyRes.DeliveredCargo {
				_, _ = s.supervisor.ExecuteTrade(ctx, verifyRes.DestinationHubID, item.CommodityID, actor.TradeActionDeliver, item.Quantity)
			}
		}
		_ = s.docStore.AppendTelemetrySample(verifyRes.RunID, verifyRes.ConvoyID, verifyRes.PlayerID, mongo.TelemetrySample{
			Tick:        verifyRes.ActionCount,
			Watts:       100,
			ReactorHeat: verifyRes.PeakReactorHeat,
			SpeedMps:    28.0,
		}, verifyRes.CoolingRodsConsumed)
	}

	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"verification": verifyRes,
		"ledger_entry": ledgerEntry,
	})
}

func (s *Server) handleGetCarriageBlueprints(c *fiber.Ctx) error {
	category := utils.CopyString(strings.TrimSpace(c.Query("slot_category")))
	bps, err := s.docStore.ListBlueprints(category)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"blueprints": bps,
		"count":      len(bps),
	})
}

func (s *Server) handleGetReleaseStatus(c *fiber.Ctx) error {
	ctx, cancel := context.WithTimeout(c.UserContext(), 3*time.Second)
	defer cancel()

	dbHealth := s.ledgerRepo.GetDatabaseHealth(ctx)
	snaps, actorErr := s.supervisor.GetAllHubSnapshots(ctx)

	hubMap := make(map[string]actor.HubMarketSnapshot, len(snaps))
	for _, snap := range snaps {
		hubMap[snap.HubID] = snap
		s.docStore.SaveOasisSnapshot(snap)
	}

	overallStatus := "ok"
	httpStatus := fiber.StatusOK
	if !dbHealth.Connected || actorErr != nil {
		overallStatus = "degraded"
		httpStatus = fiber.StatusServiceUnavailable
	}

	oasisMarket := fiber.Map{
		"hub_count":     len(snaps),
		"hubs":          snaps,
		"hydro_haven":   hubMap["hydro_haven"],
		"iron_refinery": hubMap["iron_refinery"],
		"aether_spire":  hubMap["aether_spire"],
	}
	if actorErr != nil {
		oasisMarket["error"] = actorErr.Error()
	}

	return c.Status(httpStatus).JSON(fiber.Map{
		"version":      "1.0.0",
		"service":      "chrono-convoy-backend",
		"status":       overallStatus,
		"database":     dbHealth,
		"oasis_market": oasisMarket,
		"timestamp":    time.Now().UTC().Format(time.RFC3339),
	})
}
