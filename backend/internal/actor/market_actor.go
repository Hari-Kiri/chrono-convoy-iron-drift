package actor

import (
	"context"
	"errors"
	"fmt"
	"math"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	ErrMailboxFull      = errors.New("actor mailbox full: backpressure load shedding triggered")
	ErrActorStopped     = errors.New("oasis market actor stopped")
	ErrUnknownHub       = errors.New("unknown oasis hub id")
	ErrUnknownCommodity = errors.New("unknown commodity id")
	ErrInvalidQuantity  = errors.New("quantity must be strictly positive and within bounds")
	ErrInsufficientPool = errors.New("insufficient hub commodity supply for purchase")
)

const (
	DefaultMailboxCapacity = 256
	DefaultTickInterval    = 60 * time.Second
	MaxTradeQuantity       = 100_000
	SpreadBps              = 400 // 4.0% half-spread around mid price
)

// CommoditySpec holds the static economic curve parameters from docs/design/convoy_config.json.
type CommoditySpec struct {
	ID                      string  `json:"id"`
	Name                    string  `json:"name"`
	UnitMassKg              float64 `json:"unit_mass_kg"`
	BasePrice               float64 `json:"base_price"`
	MinPrice                float64 `json:"min_price"`
	MaxPrice                float64 `json:"max_price"`
	ElasticityAlpha         float64 `json:"elasticity_alpha"`
	MeanReversionGamma      float64 `json:"mean_reversion_gamma"`
	VolatilitySigma         float64 `json:"volatility_sigma"`
	IsPrimarySinkConsumable bool    `json:"is_primary_sink_consumable"`
}

// CommodityQuote represents the isolated state of a single commodity inside an Oasis Hub.
type CommodityQuote struct {
	CommodityID      string  `json:"commodity_id"`
	Name             string  `json:"name"`
	Supply           int64   `json:"supply"`
	Demand           int64   `json:"demand"`
	EquilibriumStock int64   `json:"equilibrium_stock"`
	MidPrice         float64 `json:"mid_price"`
	BidPriceScrip    int64   `json:"bid_price_scrip"`
	AskPriceScrip    int64   `json:"ask_price_scrip"`
	DeliveriesVolume int64   `json:"deliveries_volume"`
	PurchasesVolume  int64   `json:"purchases_volume"`
}

// HubMarketSnapshot is an immutable deep copy returned from an actor query.
type HubMarketSnapshot struct {
	HubID         string                    `json:"hub_id"`
	AliasID       string                    `json:"alias_id"`
	Name          string                    `json:"name"`
	Biome         string                    `json:"biome"`
	TickEpoch     int64                     `json:"tick_epoch"`
	RestartCount  int64                     `json:"restart_count"`
	LastTickAt    time.Time                 `json:"last_tick_at"`
	Commodities   map[string]CommodityQuote `json:"commodities"`
}

// HubConfig defines initial regional multipliers and equilibrium stock for an Oasis Hub.
type HubConfig struct {
	HubID                 string
	AliasID               string
	Name                  string
	Biome                 string
	ProductionMultipliers map[string]float64
	DemandMultipliers     map[string]float64
	EquilibriumStock      map[string]int64
}

// CanonicalCommoditySpecs returns the 5 canonical commodities from convoy_config.json.
func CanonicalCommoditySpecs() map[string]CommoditySpec {
	return map[string]CommoditySpec{
		"purified_water": {
			ID: "purified_water", Name: "Purified Water", UnitMassKg: 50.0,
			BasePrice: 40.0, MinPrice: 18.0, MaxPrice: 95.0,
			ElasticityAlpha: 0.42, MeanReversionGamma: 0.18, VolatilitySigma: 0.04,
		},
		"scrap_alloy": {
			ID: "scrap_alloy", Name: "Scrap Alloy", UnitMassKg: 80.0,
			BasePrice: 55.0, MinPrice: 25.0, MaxPrice: 125.0,
			ElasticityAlpha: 0.38, MeanReversionGamma: 0.20, VolatilitySigma: 0.05,
		},
		"crude_promethium": {
			ID: "crude_promethium", Name: "Crude Promethium", UnitMassKg: 110.0,
			BasePrice: 120.0, MinPrice: 55.0, MaxPrice: 275.0,
			ElasticityAlpha: 0.50, MeanReversionGamma: 0.16, VolatilitySigma: 0.06,
		},
		"reactor_cells": {
			ID: "reactor_cells", Name: "Reactor Cells", UnitMassKg: 65.0,
			BasePrice: 185.0, MinPrice: 90.0, MaxPrice: 410.0,
			ElasticityAlpha: 0.55, MeanReversionGamma: 0.15, VolatilitySigma: 0.07,
		},
		"cooling_rods": {
			ID: "cooling_rods", Name: "Reactor Cooling Rods", UnitMassKg: 45.0,
			BasePrice: 95.0, MinPrice: 50.0, MaxPrice: 240.0,
			ElasticityAlpha: 0.60, MeanReversionGamma: 0.14, VolatilitySigma: 0.06,
			IsPrimarySinkConsumable: true,
		},
	}
}

// CanonicalHubConfigs returns the 3 Oasis Hubs from convoy_config.json.
func CanonicalHubConfigs() []HubConfig {
	return []HubConfig{
		{
			HubID:   "hydro_haven",
			AliasID: "oasis_alpha",
			Name:    "Hydro-Haven Aquifer Bastion",
			Biome:   "Subterranean Basin",
			ProductionMultipliers: map[string]float64{
				"purified_water": 1.85, "scrap_alloy": 0.90, "crude_promethium": 0.65,
				"reactor_cells": 0.80, "cooling_rods": 1.20,
			},
			DemandMultipliers: map[string]float64{
				"purified_water": 0.60, "scrap_alloy": 1.35, "crude_promethium": 1.45,
				"reactor_cells": 1.25, "cooling_rods": 0.95,
			},
			EquilibriumStock: map[string]int64{
				"purified_water": 600, "scrap_alloy": 280, "crude_promethium": 160,
				"reactor_cells": 110, "cooling_rods": 220,
			},
		},
		{
			HubID:   "iron_refinery",
			AliasID: "oasis_beta",
			Name:    "Iron-Refinery Slag Foundry",
			Biome:   "Volcanic Rust Canyon",
			ProductionMultipliers: map[string]float64{
				"purified_water": 0.55, "scrap_alloy": 1.90, "crude_promethium": 1.60,
				"reactor_cells": 0.85, "cooling_rods": 0.70,
			},
			DemandMultipliers: map[string]float64{
				"purified_water": 1.65, "scrap_alloy": 0.60, "crude_promethium": 0.75,
				"reactor_cells": 1.40, "cooling_rods": 1.55,
			},
			EquilibriumStock: map[string]int64{
				"purified_water": 180, "scrap_alloy": 650, "crude_promethium": 420,
				"reactor_cells": 120, "cooling_rods": 140,
			},
		},
		{
			HubID:   "aether_spire",
			AliasID: "oasis_gamma",
			Name:    "Aether-Spire Core Relay",
			Biome:   "Glass Fulgarite Plateau",
			ProductionMultipliers: map[string]float64{
				"purified_water": 0.70, "scrap_alloy": 0.75, "crude_promethium": 0.80,
				"reactor_cells": 1.80, "cooling_rods": 1.65,
			},
			DemandMultipliers: map[string]float64{
				"purified_water": 1.40, "scrap_alloy": 1.50, "crude_promethium": 1.55,
				"reactor_cells": 0.65, "cooling_rods": 0.75,
			},
			EquilibriumStock: map[string]int64{
				"purified_water": 210, "scrap_alloy": 240, "crude_promethium": 190,
				"reactor_cells": 450, "cooling_rods": 380,
			},
		},
	}
}

// NormalizeHubID maps any hub alias (e.g. oasis_alpha, OASIS_ALPHA_RUST_GATE, hydro_haven) to its canonical HubID.
func NormalizeHubID(raw string) (string, error) {
	cleaned := strings.ToLower(strings.TrimSpace(raw))
	switch cleaned {
	case "hydro_haven", "oasis_alpha", "oasis_alpha_rust_gate", "helios_spire", "hub_1", "alpha":
		return "hydro_haven", nil
	case "iron_refinery", "oasis_beta", "oasis_beta_slag_basin", "rustwater_basin", "hub_2", "beta":
		return "iron_refinery", nil
	case "aether_spire", "oasis_gamma", "oasis_gamma_glass_spire", "cinder_vault", "hub_3", "gamma":
		return "aether_spire", nil
	default:
		return "", fmt.Errorf("%w: %s", ErrUnknownHub, raw)
	}
}

// NormalizeCommodityID maps any commodity alias to one of the 5 canonical commodity IDs.
func NormalizeCommodityID(raw string) (string, error) {
	cleaned := strings.ToLower(strings.TrimSpace(raw))
	switch cleaned {
	case "purified_water", "water":
		return "purified_water", nil
	case "scrap_alloy", "scrap_iron", "iron_ore", "refined_alloy":
		return "scrap_alloy", nil
	case "crude_promethium", "crude_fuel", "tungsten_shells", "artillery_shells":
		return "crude_promethium", nil
	case "reactor_cells", "chrono_cells", "chrono_crystals":
		return "reactor_cells", nil
	case "cooling_rods", "cooling_rod":
		return "cooling_rods", nil
	default:
		return "", fmt.Errorf("%w: %s", ErrUnknownCommodity, raw)
	}
}

// MarketMessage is the sealed interface for all messages processed by an OasisMarketActor mailbox.
type MarketMessage interface {
	isMarketMessage()
}

// GetSnapshotMsg requests an immutable snapshot of the actor's market state.
type GetSnapshotMsg struct {
	ReplyTo chan HubMarketSnapshot
}

func (GetSnapshotMsg) isMarketMessage() {}

// TradeActionType distinguishes player deliveries (selling into Oasis) from purchases (buying from Oasis).
type TradeActionType string

const (
	TradeActionDeliver TradeActionType = "DELIVER" // Player sells/delivers cargo to hub -> increases supply, lowers price
	TradeActionBuy     TradeActionType = "BUY"     // Player buys cargo from hub -> decreases supply, raises price
)

// TradeExecutionResult is returned asynchronously over a reply channel from the actor.
type TradeExecutionResult struct {
	HubID          string          `json:"hub_id"`
	CommodityID    string          `json:"commodity_id"`
	Action         TradeActionType `json:"action"`
	Quantity       int             `json:"quantity"`
	UnitPriceScrip int64           `json:"unit_price_scrip"`
	GrossScrip     int64           `json:"gross_scrip"`
	TaxScrip       int64           `json:"tax_scrip"`
	NetScripDelta  int64           `json:"net_scrip_delta"`
	UpdatedQuote   CommodityQuote  `json:"updated_quote"`
	Err            error           `json:"-"`
}

// ExecuteTradeMsg mutates commodity supply/demand and quotes deterministic execution price.
type ExecuteTradeMsg struct {
	CommodityID string
	Action      TradeActionType
	Quantity    int
	ReplyTo     chan TradeExecutionResult
}

func (ExecuteTradeMsg) isMarketMessage() {}

// RecalcTickMsg triggers the 60-second dynamic supply/demand and mean-reversion price recalculation.
type RecalcTickMsg struct {
	TickTime time.Time
	Ack      chan HubMarketSnapshot
}

func (RecalcTickMsg) isMarketMessage() {}

// SimulatePanicMsg allows QA/tests to verify One-For-One supervision fault recovery.
type SimulatePanicMsg struct {
	Reason string
}

func (SimulatePanicMsg) isMarketMessage() {}

// OasisMarketActor encapsulates a single Oasis Hub's state inside an isolated goroutine with a bounded mailbox.
type OasisMarketActor struct {
	cfg          HubConfig
	specs        map[string]CommoditySpec
	mailbox      chan MarketMessage
	restartCount atomic.Int64
}

func newOasisMarketActor(cfg HubConfig, specs map[string]CommoditySpec, mailboxCap int) *OasisMarketActor {
	if mailboxCap <= 0 {
		mailboxCap = DefaultMailboxCapacity
	}
	return &OasisMarketActor{
		cfg:     cfg,
		specs:   specs,
		mailbox: make(chan MarketMessage, mailboxCap),
	}
}

// buildInitialState initializes the actor-private mutable state (never shared outside actor goroutine).
func (a *OasisMarketActor) buildInitialState() *HubMarketSnapshot {
	quotes := make(map[string]CommodityQuote, len(a.specs))
	for cid, spec := range a.specs {
		eq := a.cfg.EquilibriumStock[cid]
		if eq <= 0 {
			eq = 200
		}
		prodMult := a.cfg.ProductionMultipliers[cid]
		if prodMult <= 0 {
			prodMult = 1.0
		}
		demMult := a.cfg.DemandMultipliers[cid]
		if demMult <= 0 {
			demMult = 1.0
		}

		initialSupply := int64(math.Round(float64(eq) * prodMult))
		initialDemand := int64(math.Round(float64(eq) * demMult))
		mid := computeNextPrice(spec, spec.BasePrice, initialSupply, initialDemand, eq)
		bid, ask := computeBidAsk(mid)

		quotes[cid] = CommodityQuote{
			CommodityID:      cid,
			Name:             spec.Name,
			Supply:           initialSupply,
			Demand:           initialDemand,
			EquilibriumStock: eq,
			MidPrice:         mid,
			BidPriceScrip:    bid,
			AskPriceScrip:    ask,
		}
	}

	return &HubMarketSnapshot{
		HubID:       a.cfg.HubID,
		AliasID:     a.cfg.AliasID,
		Name:        a.cfg.Name,
		Biome:       a.cfg.Biome,
		TickEpoch:   1,
		LastTickAt:  time.Now().UTC(),
		Commodities: quotes,
	}
}

// computeNextPrice implements the exact DES-02 formula from docs/design/convoy_config.json:
// P(t+1) = clamp(P_base * (1 + alpha_elasticity * ((D_t - S_t) / S_eq)) + gamma_reversion * (P_base - P(t)), P_min, P_max)
func computeNextPrice(spec CommoditySpec, currentPrice float64, supply, demand, eqStock int64) float64 {
	if eqStock <= 0 {
		eqStock = 100
	}
	imbalanceRatio := float64(demand-supply) / float64(eqStock)
	targetPrice := spec.BasePrice*(1.0+spec.ElasticityAlpha*imbalanceRatio) +
		spec.MeanReversionGamma*(spec.BasePrice-currentPrice)
	if targetPrice < spec.MinPrice {
		targetPrice = spec.MinPrice
	}
	if targetPrice > spec.MaxPrice {
		targetPrice = spec.MaxPrice
	}
	return math.Round(targetPrice*100.0) / 100.0
}

func computeBidAsk(mid float64) (int64, int64) {
	rawBid := int64(math.Floor(mid * float64(10000-SpreadBps) / 10000.0))
	rawAsk := int64(math.Ceil(mid * float64(10000+SpreadBps) / 10000.0))
	if rawBid < 1 {
		rawBid = 1
	}
	if rawAsk <= rawBid {
		rawAsk = rawBid + 1
	}
	return rawBid, rawAsk
}

func cloneSnapshot(src *HubMarketSnapshot, restarts int64) HubMarketSnapshot {
	commoditiesCopy := make(map[string]CommodityQuote, len(src.Commodities))
	for k, v := range src.Commodities {
		commoditiesCopy[k] = v
	}
	return HubMarketSnapshot{
		HubID:        src.HubID,
		AliasID:      src.AliasID,
		Name:         src.Name,
		Biome:        src.Biome,
		TickEpoch:    src.TickEpoch,
		RestartCount: restarts,
		LastTickAt:   src.LastTickAt,
		Commodities:  commoditiesCopy,
	}
}

// runLoop processes messages sequentially inside a single goroutine.
// If a panic occurs, it recovers and notifies the supervisor so One-For-One restart can occur.
func (a *OasisMarketActor) runLoop(ctx context.Context, onPanic func(hubID string, r any)) {
	state := a.buildInitialState()
	defer func() {
		if r := recover(); r != nil {
			a.restartCount.Add(1)
			if onPanic != nil {
				onPanic(a.cfg.HubID, r)
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return
		case msg, ok := <-a.mailbox:
			if !ok {
				return
			}
			switch m := msg.(type) {
			case SimulatePanicMsg:
				panic("simulated actor fault: " + m.Reason)

			case GetSnapshotMsg:
				if m.ReplyTo != nil {
					m.ReplyTo <- cloneSnapshot(state, a.restartCount.Load())
				}

			case RecalcTickMsg:
				state.TickEpoch++
				if !m.TickTime.IsZero() {
					state.LastTickAt = m.TickTime
				} else {
					state.LastTickAt = time.Now().UTC()
				}
				for cid, quote := range state.Commodities {
					spec := a.specs[cid]
					prodMult := a.cfg.ProductionMultipliers[cid]
					demMult := a.cfg.DemandMultipliers[cid]
					targetSupply := int64(math.Round(float64(quote.EquilibriumStock) * prodMult))
					targetDemand := int64(math.Round(float64(quote.EquilibriumStock) * demMult))

					// Mean-reversion of supply/demand toward regional equilibrium on 60s tick
					supplyStep := int64(math.Round(float64(targetSupply-quote.Supply) * spec.MeanReversionGamma))
					demandStep := int64(math.Round(float64(targetDemand-quote.Demand) * spec.MeanReversionGamma))
					quote.Supply += supplyStep
					quote.Demand += demandStep

					// Mandatory reactor cooling rod regional sink burn every 60s tick
					if spec.IsPrimarySinkConsumable {
						sinkBurn := int64(math.Max(2.0, math.Round(float64(quote.EquilibriumStock)*0.03)))
						if quote.Supply > sinkBurn+10 {
							quote.Supply -= sinkBurn
						}
						quote.Demand += sinkBurn / 2
					}
					if quote.Supply < 10 {
						quote.Supply = 10
					}
					if quote.Demand < 10 {
						quote.Demand = 10
					}

					quote.MidPrice = computeNextPrice(spec, quote.MidPrice, quote.Supply, quote.Demand, quote.EquilibriumStock)
					quote.BidPriceScrip, quote.AskPriceScrip = computeBidAsk(quote.MidPrice)
					state.Commodities[cid] = quote
				}
				if m.Ack != nil {
					m.Ack <- cloneSnapshot(state, a.restartCount.Load())
				}

			case ExecuteTradeMsg:
				res := a.handleTrade(state, m)
				if m.ReplyTo != nil {
					m.ReplyTo <- res
				}
			}
		}
	}
}

func (a *OasisMarketActor) handleTrade(state *HubMarketSnapshot, m ExecuteTradeMsg) TradeExecutionResult {
	cid, err := NormalizeCommodityID(m.CommodityID)
	if err != nil {
		return TradeExecutionResult{Err: err}
	}
	if m.Quantity <= 0 || m.Quantity > MaxTradeQuantity {
		return TradeExecutionResult{Err: fmt.Errorf("%w: %d", ErrInvalidQuantity, m.Quantity)}
	}
	quote, exists := state.Commodities[cid]
	if !exists {
		return TradeExecutionResult{Err: fmt.Errorf("%w: %s", ErrUnknownCommodity, cid)}
	}
	spec := a.specs[cid]
	qty64 := int64(m.Quantity)

	switch m.Action {
	case TradeActionDeliver:
		// Player delivers/sells commodity into the Oasis Hub:
		// Execute at current Bid price, then increase hub Supply and recalculate spot price.
		unitBid := quote.BidPriceScrip
		gross := unitBid * qty64
		tax := (gross*300 + 9999) / 10000 // 3% Oasis infrastructure tax (ceiling rounded)
		if tax < 1 {
			tax = 1
		}
		netScrip := gross - tax
		if netScrip < 0 {
			netScrip = 0
		}

		quote.Supply += qty64
		if quote.Demand > qty64/2+10 {
			quote.Demand -= qty64 / 2
		}
		quote.DeliveriesVolume += qty64
		quote.MidPrice = computeNextPrice(spec, quote.MidPrice, quote.Supply, quote.Demand, quote.EquilibriumStock)
		quote.BidPriceScrip, quote.AskPriceScrip = computeBidAsk(quote.MidPrice)
		state.Commodities[cid] = quote

		return TradeExecutionResult{
			HubID:          state.HubID,
			CommodityID:    cid,
			Action:         TradeActionDeliver,
			Quantity:       m.Quantity,
			UnitPriceScrip: unitBid,
			GrossScrip:     gross,
			TaxScrip:       tax,
			NetScripDelta:  netScrip,
			UpdatedQuote:   quote,
		}

	case TradeActionBuy:
		// Player purchases commodity from the Oasis Hub:
		if quote.Supply < qty64 {
			return TradeExecutionResult{
				Err: fmt.Errorf("%w: requested %d of %s, hub %s has %d", ErrInsufficientPool, m.Quantity, cid, state.HubID, quote.Supply),
			}
		}
		unitAsk := quote.AskPriceScrip
		gross := unitAsk * qty64
		tax := (gross*300 + 9999) / 10000 // 3% Oasis infrastructure tax (ceiling rounded)
		if tax < 1 {
			tax = 1
		}
		totalCost := gross + tax

		quote.Supply -= qty64
		quote.Demand += qty64
		quote.PurchasesVolume += qty64
		quote.MidPrice = computeNextPrice(spec, quote.MidPrice, quote.Supply, quote.Demand, quote.EquilibriumStock)
		quote.BidPriceScrip, quote.AskPriceScrip = computeBidAsk(quote.MidPrice)
		state.Commodities[cid] = quote

		return TradeExecutionResult{
			HubID:          state.HubID,
			CommodityID:    cid,
			Action:         TradeActionBuy,
			Quantity:       m.Quantity,
			UnitPriceScrip: unitAsk,
			GrossScrip:     gross,
			TaxScrip:       tax,
			NetScripDelta:  -totalCost,
			UpdatedQuote:   quote,
		}

	default:
		return TradeExecutionResult{Err: fmt.Errorf("unsupported trade action: %s", m.Action)}
	}
}

// RegionMarketSupervisor supervises the 3 OasisMarketActor child actors using a One-For-One strategy.
type RegionMarketSupervisor struct {
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
	actors          map[string]*OasisMarketActor
	tickInterval    time.Duration
	deadLetterCount atomic.Int64
	restartEvents   chan string
}

// NewRegionMarketSupervisor constructs and starts the supervision tree for all 3 Oasis Hubs.
func NewRegionMarketSupervisor(parentCtx context.Context, mailboxCap int, tickInterval time.Duration) *RegionMarketSupervisor {
	if mailboxCap <= 0 {
		mailboxCap = DefaultMailboxCapacity
	}
	if tickInterval <= 0 {
		tickInterval = DefaultTickInterval
	}
	ctx, cancel := context.WithCancel(parentCtx)
	specs := CanonicalCommoditySpecs()
	hubConfigs := CanonicalHubConfigs()

	actors := make(map[string]*OasisMarketActor, len(hubConfigs))
	for _, hcfg := range hubConfigs {
		actors[hcfg.HubID] = newOasisMarketActor(hcfg, specs, mailboxCap)
	}

	sup := &RegionMarketSupervisor{
		ctx:           ctx,
		cancel:        cancel,
		actors:        actors,
		tickInterval:  tickInterval,
		restartEvents: make(chan string, 64),
	}

	// Launch supervised child actors (One-For-One restart on panic)
	for _, child := range sup.actors {
		sup.spawnSupervisedChild(child)
	}

	// Launch 60s regional market recalculation ticker
	sup.wg.Add(1)
	go sup.runTickerLoop()

	return sup
}

func (s *RegionMarketSupervisor) spawnSupervisedChild(child *OasisMarketActor) {
	s.wg.Add(1)
	go func(actor *OasisMarketActor) {
		defer s.wg.Done()
		for {
			if s.ctx.Err() != nil {
				return
			}
			panicked := false
			actor.runLoop(s.ctx, func(hubID string, _ any) {
				panicked = true
				select {
				case s.restartEvents <- hubID:
				default:
				}
			})
			if !panicked || s.ctx.Err() != nil {
				return
			}
			// One-For-One immediate supervisor restart with brief backoff guard
			select {
			case <-s.ctx.Done():
				return
			case <-time.After(5 * time.Millisecond):
			}
		}
	}(child)
}

func (s *RegionMarketSupervisor) runTickerLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(s.tickInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case now := <-ticker.C:
			for _, child := range s.actors {
				_ = s.enqueueMessage(child, RecalcTickMsg{TickTime: now.UTC()})
			}
		}
	}
}

// enqueueMessage enforces bounded-mailbox backpressure (load shedding) without blocking callers indefinitely.
func (s *RegionMarketSupervisor) enqueueMessage(child *OasisMarketActor, msg MarketMessage) error {
	if s.ctx.Err() != nil {
		return ErrActorStopped
	}
	select {
	case child.mailbox <- msg:
		return nil
	default:
		s.deadLetterCount.Add(1)
		return ErrMailboxFull
	}
}

// GetHubSnapshot queries a single Oasis Hub actor for an isolated snapshot of its market state.
func (s *RegionMarketSupervisor) GetHubSnapshot(ctx context.Context, rawHubID string) (HubMarketSnapshot, error) {
	hubID, err := NormalizeHubID(rawHubID)
	if err != nil {
		return HubMarketSnapshot{}, err
	}
	child := s.actors[hubID]
	replyCh := make(chan HubMarketSnapshot, 1)
	if err := s.enqueueMessage(child, GetSnapshotMsg{ReplyTo: replyCh}); err != nil {
		return HubMarketSnapshot{}, err
	}
	select {
	case <-ctx.Done():
		return HubMarketSnapshot{}, ctx.Err()
	case <-s.ctx.Done():
		return HubMarketSnapshot{}, ErrActorStopped
	case snap := <-replyCh:
		return snap, nil
	}
}

// GetAllHubSnapshots queries all 3 Oasis Hub actors concurrently and returns their snapshots in canonical order.
func (s *RegionMarketSupervisor) GetAllHubSnapshots(ctx context.Context) ([]HubMarketSnapshot, error) {
	order := []string{"hydro_haven", "iron_refinery", "aether_spire"}
	results := make([]HubMarketSnapshot, 0, len(order))
	for _, hubID := range order {
		snap, err := s.GetHubSnapshot(ctx, hubID)
		if err != nil {
			return nil, err
		}
		results = append(results, snap)
	}
	return results, nil
}

// ExecuteTrade sends an asynchronous trade/delivery message to the target Oasis Hub actor and awaits its quote.
func (s *RegionMarketSupervisor) ExecuteTrade(ctx context.Context, rawHubID, rawCommodityID string, action TradeActionType, quantity int) (TradeExecutionResult, error) {
	hubID, err := NormalizeHubID(rawHubID)
	if err != nil {
		return TradeExecutionResult{}, err
	}
	child := s.actors[hubID]
	replyCh := make(chan TradeExecutionResult, 1)
	msg := ExecuteTradeMsg{
		CommodityID: rawCommodityID,
		Action:      action,
		Quantity:    quantity,
		ReplyTo:     replyCh,
	}
	if err := s.enqueueMessage(child, msg); err != nil {
		return TradeExecutionResult{}, err
	}
	select {
	case <-ctx.Done():
		return TradeExecutionResult{}, ctx.Err()
	case <-s.ctx.Done():
		return TradeExecutionResult{}, ErrActorStopped
	case res := <-replyCh:
		return res, res.Err
	}
}

// TriggerManualRecalcTick dispatches a synchronous RecalcTickMsg across all 3 hubs (useful for deterministic tests).
func (s *RegionMarketSupervisor) TriggerManualRecalcTick(ctx context.Context, tickTime time.Time) ([]HubMarketSnapshot, error) {
	order := []string{"hydro_haven", "iron_refinery", "aether_spire"}
	snapshots := make([]HubMarketSnapshot, 0, len(order))
	for _, hubID := range order {
		child := s.actors[hubID]
		ack := make(chan HubMarketSnapshot, 1)
		if err := s.enqueueMessage(child, RecalcTickMsg{TickTime: tickTime, Ack: ack}); err != nil {
			return nil, err
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case snap := <-ack:
			snapshots = append(snapshots, snap)
		}
	}
	return snapshots, nil
}

// InjectFault sends a SimulatePanicMsg to the specified hub actor to test One-For-One supervision recovery.
func (s *RegionMarketSupervisor) InjectFault(rawHubID, reason string) error {
	hubID, err := NormalizeHubID(rawHubID)
	if err != nil {
		return err
	}
	return s.enqueueMessage(s.actors[hubID], SimulatePanicMsg{Reason: reason})
}

// DeadLetterCount returns the total count of messages dropped due to bounded-mailbox backpressure.
func (s *RegionMarketSupervisor) DeadLetterCount() int64 {
	return s.deadLetterCount.Load()
}

// RestartEvents exposes a read-only channel of hub IDs that were restarted by the supervisor.
func (s *RegionMarketSupervisor) RestartEvents() <-chan string {
	return s.restartEvents
}

// Shutdown gracefully stops all child actors and waits for their goroutines to exit.
func (s *RegionMarketSupervisor) Shutdown() {
	s.cancel()
	s.wg.Wait()
}
