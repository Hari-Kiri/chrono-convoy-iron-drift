#!/usr/bin/env python3
"""
Chrono-Convoy: Iron Drift — Sprint 1 Virtual Economy & Reactor Cooling Rod Sink Monte Carlo Simulator
Ticket: [DES-02] Oasis Hub Dynamic Arbitrage & Reactor Cooling Rod Sink Model (z8rctemh4n)
Author: @Droplet (Game Designer)

Simulates 1,000 Monte Carlo runs across:
  - 3 Oasis Hubs: Hydro-Haven, Iron-Refinery, Aether-Spire
  - 5 Commodities: Purified Water, Scrap Alloy, Crude Promethium, Reactor Cells, Cooling Rods
  - 60-second Go Actor price ticks (60 ticks = 1hr session / 15 sector transits per run)
  - Physics-coupled cargo mass -> momentum penalty -> combat exposure -> Reactor Cooling Rod burn sink
"""

import json
import math
import os
import random
import statistics
from pathlib import Path
from typing import Dict, List, Any


CONFIG_PATH = Path(__file__).resolve().parent / "convoy_config.json"
RESULTS_PATH = Path(__file__).resolve().parent / "economy_simulation_results.json"


def percentile(sorted_vals: List[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[int(k)]
    return sorted_vals[f] * (c - k) + sorted_vals[c] * (k - f)


def summarize_distribution(vals: List[float]) -> Dict[str, float]:
    s = sorted(vals)
    return {
        "min": round(s[0], 3),
        "p10": round(percentile(s, 0.10), 3),
        "p50": round(percentile(s, 0.50), 3),
        "mean": round(statistics.fmean(s), 3),
        "p90": round(percentile(s, 0.90), 3),
        "max": round(s[-1], 3),
        "std": round(statistics.pstdev(s), 3),
    }


class OasisEconomySimulator:
    def __init__(self, config: Dict[str, Any], seed: int = 42069):
        self.cfg = config
        self.rng = random.Random(seed)
        self.econ = config["economy"]
        self.loco = config["locomotive"]
        self.reactor = config["reactor"]
        self.curves = config["mass_to_momentum_curves"]
        self.track = config["spline_track"]
        self.commodities = {c["id"]: c for c in self.econ["commodities"]}
        self.hubs = {h["id"]: h for h in self.econ["oasis_hubs"]}
        self.hub_ids = list(self.hubs.keys())
        self.raiders = config["raiders"]
        self.sink_cfg = self.econ["cooling_rod_sink_model"]

    def init_hub_market_state(self) -> Dict[str, Dict[str, Dict[str, float]]]:
        state: Dict[str, Dict[str, Dict[str, float]]] = {}
        for hub_id, hub in self.hubs.items():
            state[hub_id] = {}
            for cid, c in self.commodities.items():
                eq_stock = float(hub["equilibrium_stock"][cid])
                prod_mult = float(hub["production_multipliers"][cid])
                dem_mult = float(hub["demand_multipliers"][cid])
                # Initial hub price reflects local structural supply vs demand
                imbalance = (dem_mult - prod_mult) / max(0.5, (dem_mult + prod_mult) * 0.5)
                init_price = c["base_price"] * (1.0 + 0.32 * imbalance)
                init_price = max(c["min_price"], min(c["max_price"], init_price))
                state[hub_id][cid] = {
                    "stock": eq_stock * (prod_mult / max(0.6, dem_mult)) ** 0.35,
                    "eq_stock": eq_stock,
                    "price": init_price,
                    "demand_rate": eq_stock * 0.08 * dem_mult,
                    "production_rate": eq_stock * 0.08 * prod_mult,
                }
        return state

    def step_actor_price_tick(
        self,
        market: Dict[str, Dict[str, Dict[str, float]]],
        player_trade_delta: Dict[str, Dict[str, float]],
    ) -> None:
        """
        Executes one 60-second Go Actor market tick across all 3 Oasis Hubs and 5 commodities.
        Applies production, regional consumption, player arbitrage volume dampening,
        mean reversion, and bounded stochastic noise.
        """
        for hub_id, hub in self.hubs.items():
            for cid, c in self.commodities.items():
                cell = market[hub_id][cid]
                eq_s = cell["eq_stock"]
                trade_delta = player_trade_delta.get(hub_id, {}).get(cid, 0.0)

                # Stock updates from endogenous hub production/demand + player trade (+ sold to hub, - bought from hub)
                net_endogenous = (cell["production_rate"] - cell["demand_rate"]) * 0.25
                # Arbitrage dampening pulls extreme stock back toward equilibrium
                stock_reversion = 0.18 * (eq_s - cell["stock"])
                new_stock = max(0.20 * eq_s, min(2.50 * eq_s, cell["stock"] + net_endogenous + trade_delta + stock_reversion))

                # Demand vs Supply ratio relative to equilibrium stock
                effective_demand = eq_s * float(hub["demand_multipliers"][cid])
                imbalance_ratio = (effective_demand - new_stock) / eq_s

                alpha = float(c["elasticity_alpha"])
                gamma = float(c["mean_reversion_gamma"])
                sigma = float(c["volatility_sigma"])
                base_p = float(c["base_price"])

                noise = self.rng.gauss(0.0, sigma * base_p * 0.35)
                target_p = base_p * (1.0 + alpha * imbalance_ratio)
                next_p = cell["price"] + 0.45 * (target_p - cell["price"]) + gamma * (base_p - cell["price"]) + noise
                cell["price"] = max(float(c["min_price"]), min(float(c["max_price"]), next_p))
                cell["stock"] = new_stock

    def simulate_single_run(self, ticks: int = 60, sectors_per_run: int = 15) -> Dict[str, Any]:
        """
        Simulates a 60-tick (60-minute) campaign session consisting of 15 sector transits
        (each sector transit takes 4 actor ticks = 240 seconds).
        """
        market = self.init_hub_market_state()
        credits = float(self.econ["starting_player_credits"])

        total_faucets = 0.0
        faucet_arbitrage_gross = 0.0
        faucet_bounties = 0.0
        faucet_salvage = 0.0

        total_sinks = 0.0
        sink_cooling_rods = 0.0
        sink_hull_repair = 0.0
        sink_ammo_fab = 0.0
        sink_market_taxes = 0.0
        sink_hub_infra_and_maint = 0.0

        total_rods_burned = 0.0
        commodity_price_samples: Dict[str, List[float]] = {cid: [] for cid in self.commodities}

        # Standard Sprint 1 3-carriage convoy dry mass
        convoy_dry_mass_kg = float(self.loco["dry_mass_kg"]) + (8500.0 + 11000.0 + 12500.0)
        max_cargo_kg = 2000.0 + 12000.0 + 2500.0  # 16,500 kg capacity

        current_hub_idx = self.rng.randint(0, len(self.hub_ids) - 1)
        ticks_per_sector = max(1, ticks // sectors_per_run)

        initial_price_basket = self._compute_basket_index(market)

        for sector_idx in range(sectors_per_run):
            origin_hub = self.hub_ids[current_hub_idx]
            dest_hub_idx = (current_hub_idx + self.rng.choice([1, 2])) % len(self.hub_ids)
            dest_hub = self.hub_ids[dest_hub_idx]

            # 1. Pick best arbitrage commodity between origin_hub and dest_hub (excluding cooling_rods which are bought as fuel/sink)
            best_cid = "purified_water"
            best_margin_per_kg = -1e9
            for cid, c in self.commodities.items():
                if cid == "cooling_rods":
                    continue
                buy_p = market[origin_hub][cid]["price"]
                sell_p = market[dest_hub][cid]["price"]
                margin_per_kg = (sell_p * (1.0 - self.econ["market_transaction_fee_pct"]) - buy_p * (1.0 + self.econ["market_transaction_fee_pct"])) / c["unit_mass_kg"]
                if margin_per_kg > best_margin_per_kg:
                    best_margin_per_kg = margin_per_kg
                    best_cid = cid

            c_spec = self.commodities[best_cid]
            buy_price = market[origin_hub][best_cid]["price"]
            fee_pct = float(self.econ["market_transaction_fee_pct"])

            # Player allocates up to 55% of liquid credits or 75% cargo capacity to trade haul
            affordable_units = int((credits * 0.55) / (buy_price * (1.0 + fee_pct)))
            capacity_units = int((max_cargo_kg * 0.75) / c_spec["unit_mass_kg"])
            units_bought = max(4, min(affordable_units, capacity_units, 95))

            purchase_cost = units_bought * buy_price
            buy_tax = purchase_cost * fee_pct
            cargo_mass_kg = units_bought * c_spec["unit_mass_kg"]

            # 2. Physics-coupled momentum & branch selection (Scrap Canyon vs Salt Flats)
            choose_high_threat = self.rng.random() < 0.52
            branch_cfg = (
                self.track["branches"]["high_threat_scrap_canyon"]
                if choose_high_threat
                else self.track["branches"]["low_yield_salt_flats"]
            )

            total_mass_kg = convoy_dry_mass_kg + cargo_mass_kg
            mass_ratio = total_mass_kg / convoy_dry_mass_kg  # ~1.0 to 1.29

            # Advance 4 actor ticks for the sector transit
            for t in range(ticks_per_sector):
                trade_delta: Dict[str, Dict[str, float]] = {h: {} for h in self.hub_ids}
                if t == 0:
                    trade_delta[origin_hub][best_cid] = -float(units_bought)
                elif t == ticks_per_sector - 1:
                    trade_delta[dest_hub][best_cid] = float(units_bought)
                self.step_actor_price_tick(market, trade_delta)
                for cid in self.commodities:
                    for hid in self.hub_ids:
                        commodity_price_samples[cid].append(market[hid][cid]["price"])

            # Sell cargo at destination hub
            sell_price = market[dest_hub][best_cid]["price"]
            gross_revenue = units_bought * sell_price
            sell_tax = gross_revenue * fee_pct
            net_arbitrage_profit = max(0.0, gross_revenue - purchase_cost)

            faucet_arbitrage_gross += net_arbitrage_profit
            sink_market_taxes += (buy_tax + sell_tax)

            # 3. Combat Encounter Faucets (Raider Bounties & Salvage)
            threat_mult = float(branch_cfg["threat_multiplier"])
            salvage_mult = float(branch_cfg["salvage_reward_multiplier"])
            encounters = self.rng.randint(3, 6) if choose_high_threat else self.rng.randint(2, 4)

            sector_bounties = 0.0
            sector_salvage_value = 0.0
            rods_looted = 0.0

            for _ in range(encounters):
                raider = self.rng.choice(self.raiders)
                sector_bounties += float(raider["bounty_credits"]) * (0.85 + 0.30 * self.rng.random())
                # Scrap & cell drops valued at local hub price * salvage multiplier
                scrap_lt = raider["loot_table"]["scrap_alloy"]
                if self.rng.random() < scrap_lt["chance"]:
                    qty = self.rng.randint(scrap_lt["min_qty"], scrap_lt["max_qty"])
                    sector_salvage_value += qty * market[dest_hub]["scrap_alloy"]["price"] * 0.42 * salvage_mult
                cell_lt = raider["loot_table"]["reactor_cells"]
                if self.rng.random() < cell_lt["chance"]:
                    qty = self.rng.randint(cell_lt["min_qty"], cell_lt["max_qty"])
                    sector_salvage_value += qty * market[dest_hub]["reactor_cells"]["price"] * 0.35 * salvage_mult
                rod_lt = raider["loot_table"]["cooling_rods"]
                if self.rng.random() < (rod_lt["chance"] + float(branch_cfg["cooling_rod_drop_bonus"])):
                    rods_looted += 1.0

            faucet_bounties += sector_bounties
            faucet_salvage += sector_salvage_value

            # 4. Sinks: Reactor Cooling Rods, Hull Repair, Ammo Fabrication, Hub Infrastructure
            baseline_rods = float(self.sink_cfg["mandatory_baseline_rods_per_sector"])
            overclock_rods = (
                float(self.sink_cfg["overclock_rods_per_high_threat_sector"]) * (0.85 + 0.35 * self.rng.random())
                if choose_high_threat
                else 0.85 * (0.7 + 0.4 * self.rng.random())
            )
            cargo_thermal_rods = (cargo_mass_kg / 10000.0) * float(self.sink_cfg["cargo_mass_thermal_penalty_rods_per_10t"])
            rods_consumed = baseline_rods + overclock_rods + cargo_thermal_rods
            net_rods_purchased = max(0.5, rods_consumed - 0.35 * rods_looted)

            rod_unit_price = market[dest_hub]["cooling_rods"]["price"]
            rod_sink_credits = net_rods_purchased * rod_unit_price * float(self.reactor["overclock"]["cooling_rod_sink_decay"]["overclock_wear_multiplier"])

            # Hull damage scales with threat multiplier and heavier mass (slower acceleration -> longer exposure)
            hp_damaged = (115.0 * threat_mult * (mass_ratio ** 1.15)) * (0.80 + 0.40 * self.rng.random())
            repair_sink_credits = hp_damaged * float(self.econ["hull_repair_cost_credits_per_hp"])

            # Ammo fabrication cost scales with encounter count and threat
            ammo_sink_credits = encounters * 24.5 * threat_mult * (0.85 + 0.30 * self.rng.random())

            # Oasis Hub docking, coolant flush & progressive carriage maintenance sink
            sector_gross_faucet = net_arbitrage_profit + sector_bounties + sector_salvage_value
            infra_sink_credits = (
                sector_gross_faucet * float(self.econ["oasis_infrastructure_tax_pct"])
                + 0.45 * float(self.sink_cfg["hub_emergency_coolant_flush_fee_credits"])
                + max(0.0, (credits - 1000.0) * 0.022)
            )

            total_rods_burned += rods_consumed
            sink_cooling_rods += rod_sink_credits
            sink_hull_repair += repair_sink_credits
            sink_ammo_fab += ammo_sink_credits
            sink_hub_infra_and_maint += infra_sink_credits

            sector_total_sink = rod_sink_credits + repair_sink_credits + ammo_sink_credits + buy_tax + sell_tax + infra_sink_credits
            total_faucets += sector_gross_faucet
            total_sinks += sector_total_sink

            credits = max(150.0, credits + sector_gross_faucet - sector_total_sink)
            current_hub_idx = dest_hub_idx

        final_price_basket = self._compute_basket_index(market)
        inflation_index = final_price_basket / max(1e-6, initial_price_basket)
        sink_to_faucet_ratio = total_sinks / max(1e-6, total_faucets)
        net_currency_velocity_per_hr = total_faucets - total_sinks

        return {
            "final_credits": credits,
            "total_faucets": total_faucets,
            "total_sinks": total_sinks,
            "net_currency_velocity_per_hr": net_currency_velocity_per_hr,
            "sink_to_faucet_ratio": sink_to_faucet_ratio,
            "inflation_index": inflation_index,
            "total_rods_burned": total_rods_burned,
            "cooling_rod_share_of_sinks": sink_cooling_rods / max(1e-6, total_sinks),
            "faucet_breakdown": {
                "arbitrage": faucet_arbitrage_gross,
                "bounties": faucet_bounties,
                "salvage": faucet_salvage,
            },
            "sink_breakdown": {
                "cooling_rods": sink_cooling_rods,
                "hull_repair": sink_hull_repair,
                "ammo_fabrication": sink_ammo_fab,
                "market_taxes": sink_market_taxes,
                "hub_infra_and_maint": sink_hub_infra_and_maint,
            },
            "commodity_price_samples": commodity_price_samples,
        }

    def _compute_basket_index(self, market: Dict[str, Dict[str, Dict[str, float]]]) -> float:
        total = 0.0
        count = 0
        for hid in self.hub_ids:
            for cid in self.commodities:
                total += market[hid][cid]["price"] / float(self.commodities[cid]["base_price"])
                count += 1
        return total / max(1, count)

    def run_monte_carlo(self, n_runs: int = 1000, ticks_per_run: int = 60) -> Dict[str, Any]:
        runs = [self.simulate_single_run(ticks=ticks_per_run, sectors_per_run=15) for _ in range(n_runs)]

        all_commodity_prices: Dict[str, List[float]] = {cid: [] for cid in self.commodities}
        for r in runs:
            for cid, samples in r["commodity_price_samples"].items():
                all_commodity_prices[cid].extend(samples)

        summary = {
            "simulation_metadata": {
                "n_runs": n_runs,
                "ticks_per_run": ticks_per_run,
                "tick_duration_sec": self.econ["actor_tick_interval_sec"],
                "sectors_per_run": 15,
                "seed": 42069,
                "config_version": self.cfg["version"],
            },
            "metrics": {
                "total_faucets_credits": summarize_distribution([r["total_faucets"] for r in runs]),
                "total_sinks_credits": summarize_distribution([r["total_sinks"] for r in runs]),
                "net_currency_velocity_credits_per_hr": summarize_distribution([r["net_currency_velocity_per_hr"] for r in runs]),
                "final_player_credits": summarize_distribution([r["final_credits"] for r in runs]),
                "sink_to_faucet_ratio": summarize_distribution([r["sink_to_faucet_ratio"] for r in runs]),
                "inflation_index_60_ticks": summarize_distribution([r["inflation_index"] for r in runs]),
                "cooling_rods_burned_per_run": summarize_distribution([r["total_rods_burned"] for r in runs]),
                "cooling_rod_share_of_total_sinks": summarize_distribution([r["cooling_rod_share_of_sinks"] for r in runs]),
            },
            "mean_faucet_breakdown_credits": {
                "oasis_arbitrage": round(statistics.fmean([r["faucet_breakdown"]["arbitrage"] for r in runs]), 2),
                "raider_bounties": round(statistics.fmean([r["faucet_breakdown"]["bounties"] for r in runs]), 2),
                "combat_salvage": round(statistics.fmean([r["faucet_breakdown"]["salvage"] for r in runs]), 2),
            },
            "mean_sink_breakdown_credits": {
                "reactor_cooling_rods": round(statistics.fmean([r["sink_breakdown"]["cooling_rods"] for r in runs]), 2),
                "hull_repair": round(statistics.fmean([r["sink_breakdown"]["hull_repair"] for r in runs]), 2),
                "ammo_fabrication": round(statistics.fmean([r["sink_breakdown"]["ammo_fabrication"] for r in runs]), 2),
                "market_transaction_fee_5pct": round(statistics.fmean([r["sink_breakdown"]["market_taxes"] for r in runs]), 2),
                "oasis_infra_and_maintenance": round(statistics.fmean([r["sink_breakdown"]["hub_infra_and_maint"] for r in runs]), 2),
            },
            "commodity_price_bounds_observed": {
                cid: summarize_distribution(samples)
                for cid, samples in all_commodity_prices.items()
            },
        }

        # Verify non-hyperinflationary bounds
        s2f_p50 = summary["metrics"]["sink_to_faucet_ratio"]["p50"]
        inf_p90 = summary["metrics"]["inflation_index_60_ticks"]["p90"]
        inf_p10 = summary["metrics"]["inflation_index_60_ticks"]["p10"]
        summary["verification"] = {
            "non_hyperinflationary_verified": bool(0.75 <= s2f_p50 <= 0.96 and 0.90 <= inf_p10 and inf_p90 <= 1.10),
            "target_sink_to_faucet_band": [
                self.sink_cfg["target_sink_to_faucet_ratio_min"],
                self.sink_cfg["target_sink_to_faucet_ratio_max"],
            ],
            "observed_sink_to_faucet_p50": s2f_p50,
            "observed_inflation_index_p50": summary["metrics"]["inflation_index_60_ticks"]["p50"],
            "observed_inflation_index_p90": inf_p90,
        }
        return summary


def main() -> None:
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        config = json.load(f)

    sim = OasisEconomySimulator(config=config, seed=config.get("deterministic_seed_default", 42069))
    results = sim.run_monte_carlo(n_runs=1000, ticks_per_run=60)

    with open(RESULTS_PATH, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    m = results["metrics"]
    v = results["verification"]
    print("=" * 78)
    print("CHRONO-CONVOY: IRON DRIFT — MONTE CARLO ECONOMY SIMULATION (N = 1,000 RUNS)")
    print("=" * 78)
    print(f"Config Version          : {results['simulation_metadata']['config_version']}")
    print(f"Simulated Horizon       : {results['simulation_metadata']['ticks_per_run']} Actor Ticks (60s/tick = 15 Sector Hauls)")
    print("-" * 78)
    print(f"Total Faucets (Cr/hr)   : p10={m['total_faucets_credits']['p10']:.1f} | p50={m['total_faucets_credits']['p50']:.1f} | p90={m['total_faucets_credits']['p90']:.1f} (mean={m['total_faucets_credits']['mean']:.1f})")
    print(f"Total Sinks (Cr/hr)     : p10={m['total_sinks_credits']['p10']:.1f} | p50={m['total_sinks_credits']['p50']:.1f} | p90={m['total_sinks_credits']['p90']:.1f} (mean={m['total_sinks_credits']['mean']:.1f})")
    print(f"Net Velocity (Cr/hr)    : p10={m['net_currency_velocity_credits_per_hr']['p10']:.1f} | p50={m['net_currency_velocity_credits_per_hr']['p50']:.1f} | p90={m['net_currency_velocity_credits_per_hr']['p90']:.1f} (mean={m['net_currency_velocity_credits_per_hr']['mean']:.1f})")
    print(f"Sink-to-Faucet Ratio    : p10={m['sink_to_faucet_ratio']['p10']:.3f} | p50={m['sink_to_faucet_ratio']['p50']:.3f} | p90={m['sink_to_faucet_ratio']['p90']:.3f}")
    print(f"Inflation Index (60m)   : p10={m['inflation_index_60_ticks']['p10']:.4f} | p50={m['inflation_index_60_ticks']['p50']:.4f} | p90={m['inflation_index_60_ticks']['p90']:.4f}")
    print(f"Cooling Rods Burned/Run : p10={m['cooling_rods_burned_per_run']['p10']:.2f} | p50={m['cooling_rods_burned_per_run']['p50']:.2f} | p90={m['cooling_rods_burned_per_run']['p90']:.2f}")
    print(f"Cooling Rod Sink Share  : p10={m['cooling_rod_share_of_total_sinks']['p10']*100:.1f}% | p50={m['cooling_rod_share_of_total_sinks']['p50']*100:.1f}% | p90={m['cooling_rod_share_of_total_sinks']['p90']*100:.1f}%")
    print("-" * 78)
    print("MEAN FAUCET BREAKDOWN (CREDITS / RUN):")
    for k, val in results["mean_faucet_breakdown_credits"].items():
        print(f"  - {k:<28}: {val:>8.2f} Cr")
    print("MEAN SINK BREAKDOWN (CREDITS / RUN):")
    for k, val in results["mean_sink_breakdown_credits"].items():
        print(f"  - {k:<28}: {val:>8.2f} Cr")
    print("-" * 78)
    print("COMMODITY DYNAMIC PRICE BOUNDS ACROSS 3 OASIS HUBS (CREDITS):")
    for cid, cstats in results["commodity_price_bounds_observed"].items():
        print(f"  - {cid:<18}: min={cstats['min']:>6.2f} | p10={cstats['p10']:>6.2f} | p50={cstats['p50']:>6.2f} | p90={cstats['p90']:>6.2f} | max={cstats['max']:>6.2f}")
    print("-" * 78)
    print(f"NON-HYPERINFLATIONARY INVARIANT VERIFIED: {v['non_hyperinflationary_verified']}")
    print(f"Saved machine-readable report to: {RESULTS_PATH}")
    print("=" * 78)

    if not v["non_hyperinflationary_verified"]:
        raise SystemExit("ERROR: Economy failed non-hyperinflationary bounds check!")


if __name__ == "__main__":
    main()
