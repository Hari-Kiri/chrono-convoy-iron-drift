package com.chronoconvoy.client.net;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP Client for @Patrick's Go/Fiber Backend Server (http://127.0.0.1:8080 by default)
 * with automatic deterministic offline fallback when the backend is unreachable.
 */
public class BackendApiClient {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";
    public static final String DEFAULT_PLAYER_ID = "player-chronoconvoy-01";
    public static final String DEFAULT_CONVOY_ID = "convoy-iron-drift-01";

    public static final List<String> CANONICAL_COMMODITY_IDS = List.of(
        "purified_water",
        "scrap_alloy",
        "crude_promethium",
        "reactor_cells",
        "cooling_rods"
    );

    public static final class CommodityMarketQuote {
        private final String commodityId;
        private final String name;
        private final float unitMassKg;
        private long supply;
        private long demand;
        private long bidPriceScrip;
        private long askPriceScrip;
        private double midPrice;

        public CommodityMarketQuote(
                String commodityId,
                String name,
                float unitMassKg,
                long supply,
                long demand,
                long bidPriceScrip,
                long askPriceScrip,
                double midPrice) {
            this.commodityId = commodityId;
            this.name = name;
            this.unitMassKg = unitMassKg;
            this.supply = supply;
            this.demand = demand;
            this.bidPriceScrip = bidPriceScrip;
            this.askPriceScrip = askPriceScrip;
            this.midPrice = midPrice;
        }

        public String getCommodityId() { return commodityId; }
        public String getName() { return name; }
        public float getUnitMassKg() { return unitMassKg; }
        public long getSupply() { return supply; }
        public long getDemand() { return demand; }
        public long getBidPriceScrip() { return bidPriceScrip; }
        public long getAskPriceScrip() { return askPriceScrip; }
        public double getMidPrice() { return midPrice; }

        void applyTradeMutation(boolean isBuy, int quantity) {
            if (isBuy) {
                supply = Math.max(10L, supply - quantity);
                demand += Math.max(1L, quantity / 2);
                askPriceScrip = Math.round(askPriceScrip * (1.0 + Math.min(0.15, quantity * 0.005)));
                bidPriceScrip = Math.max(1L, Math.round(askPriceScrip * 0.92));
            } else {
                supply += quantity;
                askPriceScrip = Math.max(10L, Math.round(askPriceScrip * (1.0 - Math.min(0.12, quantity * 0.004))));
                bidPriceScrip = Math.max(1L, Math.round(askPriceScrip * 0.92));
            }
            midPrice = (askPriceScrip + bidPriceScrip) / 2.0;
        }
    }

    public static final class HubMarketView {
        private final String hubId;
        private final String hubName;
        private final boolean fromLiveBackend;
        private final Map<String, CommodityMarketQuote> quotes;

        public HubMarketView(
                String hubId,
                String hubName,
                boolean fromLiveBackend,
                Map<String, CommodityMarketQuote> quotes) {
            this.hubId = hubId;
            this.hubName = hubName;
            this.fromLiveBackend = fromLiveBackend;
            this.quotes = Collections.unmodifiableMap(new LinkedHashMap<>(quotes));
        }

        public String getHubId() { return hubId; }
        public String getHubName() { return hubName; }
        public boolean isFromLiveBackend() { return fromLiveBackend; }
        public Map<String, CommodityMarketQuote> getQuotes() { return quotes; }
        public CommodityMarketQuote getQuote(String commodityId) { return quotes.get(commodityId); }
    }

    public static final class TradeExecutionResult {
        private final boolean success;
        private final boolean liveBackendUsed;
        private final String idempotencyKey;
        private final String commodityId;
        private final String action;
        private final int quantity;
        private final long unitPriceScrip;
        private final long scripDelta;
        private final long scripBalanceAfter;
        private final int cargoQtyAfter;
        private final String message;

        public TradeExecutionResult(
                boolean success,
                boolean liveBackendUsed,
                String idempotencyKey,
                String commodityId,
                String action,
                int quantity,
                long unitPriceScrip,
                long scripDelta,
                long scripBalanceAfter,
                int cargoQtyAfter,
                String message) {
            this.success = success;
            this.liveBackendUsed = liveBackendUsed;
            this.idempotencyKey = idempotencyKey;
            this.commodityId = commodityId;
            this.action = action;
            this.quantity = quantity;
            this.unitPriceScrip = unitPriceScrip;
            this.scripDelta = scripDelta;
            this.scripBalanceAfter = scripBalanceAfter;
            this.cargoQtyAfter = cargoQtyAfter;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public boolean isLiveBackendUsed() { return liveBackendUsed; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public String getCommodityId() { return commodityId; }
        public String getAction() { return action; }
        public int getQuantity() { return quantity; }
        public long getUnitPriceScrip() { return unitPriceScrip; }
        public long getScripDelta() { return scripDelta; }
        public long getScripBalanceAfter() { return scripBalanceAfter; }
        public int getCargoQtyAfter() { return cargoQtyAfter; }
        public String getMessage() { return message; }
    }

    public static final class ConvoyLoadoutState {
        private final String convoyId;
        private final String playerId;
        private final String locomotiveClass;
        private final int maxReactorWatts;
        private final int maxCargoCapacity;
        private final int coolingRodBay;
        private final long walletIronScrip;
        private final boolean fromLiveBackend;
        private final List<String> carriageBlueprints;

        public ConvoyLoadoutState(
                String convoyId,
                String playerId,
                String locomotiveClass,
                int maxReactorWatts,
                int maxCargoCapacity,
                int coolingRodBay,
                long walletIronScrip,
                boolean fromLiveBackend,
                List<String> carriageBlueprints) {
            this.convoyId = convoyId;
            this.playerId = playerId;
            this.locomotiveClass = locomotiveClass;
            this.maxReactorWatts = maxReactorWatts;
            this.maxCargoCapacity = maxCargoCapacity;
            this.coolingRodBay = coolingRodBay;
            this.walletIronScrip = walletIronScrip;
            this.fromLiveBackend = fromLiveBackend;
            this.carriageBlueprints = List.copyOf(carriageBlueprints);
        }

        public String getConvoyId() { return convoyId; }
        public String getPlayerId() { return playerId; }
        public String getLocomotiveClass() { return locomotiveClass; }
        public int getMaxReactorWatts() { return maxReactorWatts; }
        public int getMaxCargoCapacity() { return maxCargoCapacity; }
        public int getCoolingRodBay() { return coolingRodBay; }
        public long getWalletIronScrip() { return walletIronScrip; }
        public boolean isFromLiveBackend() { return fromLiveBackend; }
        public List<String> getCarriageBlueprints() { return carriageBlueprints; }
    }

    public static final class RunVerifyResult {
        private final boolean verified;
        private final boolean liveBackendUsed;
        private final String runId;
        private final String finalStateHash;
        private final long rewardScrip;
        private final long scripBalanceAfter;
        private final String rawResponseJson;

        public RunVerifyResult(
                boolean verified,
                boolean liveBackendUsed,
                String runId,
                String finalStateHash,
                long rewardScrip,
                long scripBalanceAfter,
                String rawResponseJson) {
            this.verified = verified;
            this.liveBackendUsed = liveBackendUsed;
            this.runId = runId;
            this.finalStateHash = finalStateHash;
            this.rewardScrip = rewardScrip;
            this.scripBalanceAfter = scripBalanceAfter;
            this.rawResponseJson = rawResponseJson;
        }

        public boolean isVerified() { return verified; }
        public boolean isLiveBackendUsed() { return liveBackendUsed; }
        public String getRunId() { return runId; }
        public String getFinalStateHash() { return finalStateHash; }
        public long getRewardScrip() { return rewardScrip; }
        public long getScripBalanceAfter() { return scripBalanceAfter; }
        public String getRawResponseJson() { return rawResponseJson; }
    }

    private final HttpClient httpClient;
    private String baseUrl;
    private String playerId;
    private String convoyId;
    private boolean loadoutProvisionedOnServer = false;

    private long offlineIronScrip = 2500L;
    private int offlineMaxReactorWatts = 100;
    private int offlineMaxCargoCapacity = 250;
    private int offlineCoolingRodBay = 12;
    private final Map<String, Integer> offlineCargoHold = new LinkedHashMap<>();
    private final Map<String, CommodityMarketQuote> offlineMarketQuotes = new LinkedHashMap<>();
    private final List<String> offlineCarriageBlueprints = new ArrayList<>(List.of(
        "gatling_turret_car", "shield_dome_car", "cargo_smelter_car"
    ));

    public BackendApiClient() {
        this(DEFAULT_BASE_URL, DEFAULT_PLAYER_ID, DEFAULT_CONVOY_ID);
    }

    public BackendApiClient(String baseUrl) {
        this(baseUrl, DEFAULT_PLAYER_ID, DEFAULT_CONVOY_ID);
    }

    public BackendApiClient(String baseUrl, String playerId, String convoyId) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.playerId = (playerId == null || playerId.isBlank()) ? DEFAULT_PLAYER_ID : playerId.trim();
        this.convoyId = (convoyId == null || convoyId.isBlank()) ? DEFAULT_CONVOY_ID : convoyId.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1800))
                .build();
        initializeOfflineMarketQuotes();
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void initializeOfflineMarketQuotes() {
        offlineMarketQuotes.put("purified_water", new CommodityMarketQuote(
                "purified_water", "Purified Water", 50.0f, 1200L, 650L, 38L, 42L, 40.0));
        offlineMarketQuotes.put("scrap_alloy", new CommodityMarketQuote(
                "scrap_alloy", "Scrap Alloy", 80.0f, 800L, 780L, 53L, 57L, 55.0));
        offlineMarketQuotes.put("crude_promethium", new CommodityMarketQuote(
                "crude_promethium", "Crude Promethium", 110.0f, 450L, 520L, 115L, 125L, 120.0));
        offlineMarketQuotes.put("reactor_cells", new CommodityMarketQuote(
                "reactor_cells", "Reactor Cells", 65.0f, 240L, 310L, 178L, 192L, 185.0));
        offlineMarketQuotes.put("cooling_rods", new CommodityMarketQuote(
                "cooling_rods", "Reactor Cooling Rods", 45.0f, 520L, 500L, 91L, 99L, 95.0));
    }

    public static float getCanonicalUnitMassKg(String commodityId) {
        return switch (commodityId) {
            case "purified_water" -> 50.0f;
            case "scrap_alloy" -> 80.0f;
            case "crude_promethium" -> 110.0f;
            case "reactor_cells" -> 65.0f;
            case "cooling_rods" -> 45.0f;
            default -> 50.0f;
        };
    }

    public HubMarketView fetchOasisMarket(String hubId) {
        String targetHub = (hubId == null || hubId.isBlank()) ? "hydro_haven" : hubId.trim();
        String endpoint = baseUrl + "/api/v1/oasis/market/" + targetHub;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(2000))
                    .header("Accept", "application/json")
                    .header("X-Player-ID", playerId)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonValue root = new JsonReader().parse(resp.body());
                JsonValue hubNode = root.has("commodities") ? root : findHubInOverview(root, targetHub);
                if (hubNode != null && hubNode.has("commodities")) {
                    Map<String, CommodityMarketQuote> parsedQuotes = new LinkedHashMap<>();
                    JsonValue comms = hubNode.get("commodities");
                    for (JsonValue c = comms.child; c != null; c = c.next) {
                        String cid = c.getString("commodity_id", c.name);
                        String name = c.getString("name", cid);
                        long supply = c.getLong("supply", 500L);
                        long demand = c.getLong("demand", 500L);
                        long bid = c.getLong("bid_price_scrip", 40L);
                        long ask = c.getLong("ask_price_scrip", 44L);
                        double mid = c.getDouble("mid_price", (bid + ask) / 2.0);
                        CommodityMarketQuote q = new CommodityMarketQuote(
                                cid, name, getCanonicalUnitMassKg(cid), supply, demand, bid, ask, mid);
                        parsedQuotes.put(cid, q);
                        offlineMarketQuotes.put(cid, q);
                    }
                    String hName = hubNode.getString("name", "Hydro-Haven Aquifer Bastion");
                    return new HubMarketView(targetHub, hName, true, parsedQuotes);
                }
            }
        } catch (Exception ignored) {
        }
        return new HubMarketView(targetHub, "Hydro-Haven Aquifer Bastion (Offline Simulation)", false, offlineMarketQuotes);
    }

    private static JsonValue findHubInOverview(JsonValue root, String hubId) {
        if (root == null || !root.has("hubs")) {
            return null;
        }
        JsonValue hubs = root.get("hubs");
        for (JsonValue h = hubs.child; h != null; h = h.next) {
            if (hubId.equalsIgnoreCase(h.getString("hub_id", ""))
                    || hubId.equalsIgnoreCase(h.getString("alias_id", ""))) {
                return h;
            }
        }
        return hubs.child;
    }

    public ConvoyLoadoutState syncConvoyLoadout(
            int maxReactorWatts,
            int maxCargoCapacity,
            int coolingRodBay,
            List<String> carriageBlueprints) {
        this.offlineMaxReactorWatts = Math.max(20, maxReactorWatts);
        this.offlineMaxCargoCapacity = Math.max(50, maxCargoCapacity);
        this.offlineCoolingRodBay = Math.max(1, coolingRodBay);
        if (carriageBlueprints != null && !carriageBlueprints.isEmpty()) {
            this.offlineCarriageBlueprints.clear();
            this.offlineCarriageBlueprints.addAll(carriageBlueprints);
        }

        StringBuilder carriagesJson = new StringBuilder("[");
        for (int i = 0; i < offlineCarriageBlueprints.size(); i++) {
            if (i > 0) {
                carriagesJson.append(",");
            }
            String bp = offlineCarriageBlueprints.get(i);
            int wattDraw = bp.contains("shield") ? 15 : 10;
            int cargoCap = bp.contains("cargo") ? 180 : 35;
            carriagesJson.append(String.format(
                    "{\"slot\":%d,\"blueprint_id\":\"%s\",\"watt_draw\":%d,\"cargo_cap\":%d}",
                    i + 1, bp, wattDraw, cargoCap));
        }
        carriagesJson.append("]");

        String body = String.format(
                "{\"convoy_id\":\"%s\",\"player_id\":\"%s\",\"locomotive_class\":\"iron_drifter_mk1\","
                + "\"max_reactor_watts\":%d,\"max_cargo_capacity\":%d,\"cooling_rod_bay\":%d,\"carriages\":%s}",
                convoyId, playerId, offlineMaxReactorWatts, offlineMaxCargoCapacity, offlineCoolingRodBay, carriagesJson);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/convoy/loadout"))
                    .timeout(Duration.ofMillis(2000))
                    .header("Content-Type", "application/json")
                    .header("X-Player-ID", playerId)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                loadoutProvisionedOnServer = true;
                return parseLoadoutResponse(resp.body(), true);
            }
        } catch (Exception ignored) {
        }

        return new ConvoyLoadoutState(
                convoyId,
                playerId,
                "iron_drifter_mk1",
                offlineMaxReactorWatts,
                offlineMaxCargoCapacity,
                offlineCoolingRodBay,
                offlineIronScrip,
                false,
                offlineCarriageBlueprints);
    }

    public ConvoyLoadoutState fetchConvoyLoadout() {
        ensureServerLoadoutProvisioned();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/convoy/loadout?convoy_id=" + convoyId))
                    .timeout(Duration.ofMillis(2000))
                    .header("Accept", "application/json")
                    .header("X-Player-ID", playerId)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return parseLoadoutResponse(resp.body(), true);
            }
        } catch (Exception ignored) {
        }
        return new ConvoyLoadoutState(
                convoyId,
                playerId,
                "iron_drifter_mk1",
                offlineMaxReactorWatts,
                offlineMaxCargoCapacity,
                offlineCoolingRodBay,
                offlineIronScrip,
                false,
                offlineCarriageBlueprints);
    }

    private ConvoyLoadoutState parseLoadoutResponse(String jsonText, boolean live) {
        JsonValue root = new JsonReader().parse(jsonText);
        String cid = root.getString("convoy_id", convoyId);
        String pid = root.getString("player_id", playerId);
        String loco = root.getString("locomotive_class", "iron_drifter_mk1");
        int maxW = root.getInt("max_reactor_watts", offlineMaxReactorWatts);
        int maxCap = root.getInt("max_cargo_capacity", offlineMaxCargoCapacity);
        int rodBay = root.getInt("cooling_rod_bay", offlineCoolingRodBay);
        long scrip = offlineIronScrip;
        if (root.has("wallet") && root.get("wallet").has("iron_scrip")) {
            scrip = root.get("wallet").getLong("iron_scrip", offlineIronScrip);
            offlineIronScrip = scrip;
        }
        List<String> bps = new ArrayList<>();
        if (root.has("carriages")) {
            for (JsonValue c = root.get("carriages").child; c != null; c = c.next) {
                bps.add(c.getString("blueprint_id", "gatling_turret_car"));
            }
        }
        if (bps.isEmpty()) {
            bps.addAll(offlineCarriageBlueprints);
        }
        return new ConvoyLoadoutState(cid, pid, loco, maxW, maxCap, rodBay, scrip, live, bps);
    }

    private void ensureServerLoadoutProvisioned() {
        if (!loadoutProvisionedOnServer) {
            syncConvoyLoadout(offlineMaxReactorWatts, offlineMaxCargoCapacity, offlineCoolingRodBay, offlineCarriageBlueprints);
        }
    }

    public TradeExecutionResult executeMarketTrade(
            String hubId,
            String commodityId,
            String action,
            int quantity,
            String idempotencyKey) {
        String targetHub = (hubId == null || hubId.isBlank()) ? "hydro_haven" : hubId.trim();
        String normAction = "SELL".equalsIgnoreCase(action) ? "DELIVER" : action.toUpperCase().trim();
        String idemKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "trade-" + UUID.randomUUID()
                : idempotencyKey.trim();

        ensureServerLoadoutProvisioned();

        String payload = String.format(
                "{\"idempotency_key\":\"%s\",\"player_id\":\"%s\",\"convoy_id\":\"%s\","
                + "\"hub_id\":\"%s\",\"commodity_id\":\"%s\",\"action\":\"%s\",\"quantity\":%d}",
                idemKey, playerId, convoyId, targetHub, commodityId, normAction, quantity);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/oasis/market/trade"))
                    .timeout(Duration.ofMillis(2500))
                    .header("Content-Type", "application/json")
                    .header("X-Player-ID", playerId)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonValue root = new JsonReader().parse(resp.body());
                JsonValue entry = root.get("ledger_entry");
                if (entry != null) {
                    long unitPrice = entry.getLong("unit_price_scrip", 40L);
                    long scripDelta = entry.getLong("scrip_delta", 0L);
                    long scripAfter = entry.getLong("scrip_balance_after", offlineIronScrip);
                    int cargoAfter = entry.getInt("cargo_qty_after", quantity);
                    offlineIronScrip = scripAfter;
                    offlineCargoHold.put(commodityId, cargoAfter);
                    return new TradeExecutionResult(
                            true, true, idemKey, commodityId, normAction, quantity,
                            unitPrice, scripDelta, scripAfter, cargoAfter, "Committed to live ACID ledger");
                }
            }
        } catch (Exception ignored) {
        }

        CommodityMarketQuote quote = offlineMarketQuotes.get(commodityId);
        if (quote == null || quantity <= 0) {
            return new TradeExecutionResult(
                    false, false, idemKey, commodityId, normAction, quantity,
                    0L, 0L, offlineIronScrip, 0, "Unknown commodity or invalid quantity");
        }

        boolean isBuy = "BUY".equalsIgnoreCase(normAction);
        long unitPrice = isBuy ? quote.getAskPriceScrip() : quote.getBidPriceScrip();
        long totalScrip = unitPrice * quantity;
        int currentQty = offlineCargoHold.getOrDefault(commodityId, 0);

        if (isBuy) {
            if (offlineIronScrip < totalScrip) {
                return new TradeExecutionResult(
                        false, false, idemKey, commodityId, normAction, quantity,
                        unitPrice, 0L, offlineIronScrip, currentQty, "Insufficient Iron Scrip");
            }
            offlineIronScrip -= totalScrip;
            int updatedQty = currentQty + quantity;
            offlineCargoHold.put(commodityId, updatedQty);
            quote.applyTradeMutation(true, quantity);
            return new TradeExecutionResult(
                    true, false, idemKey, commodityId, normAction, quantity,
                    unitPrice, -totalScrip, offlineIronScrip, updatedQty, "Executed via deterministic offline ledger");
        } else {
            if (currentQty < quantity) {
                return new TradeExecutionResult(
                        false, false, idemKey, commodityId, normAction, quantity,
                        unitPrice, 0L, offlineIronScrip, currentQty, "Insufficient cargo quantity to sell");
            }
            offlineIronScrip += totalScrip;
            int updatedQty = currentQty - quantity;
            offlineCargoHold.put(commodityId, updatedQty);
            quote.applyTradeMutation(false, quantity);
            return new TradeExecutionResult(
                    true, false, idemKey, commodityId, normAction, quantity,
                    unitPrice, totalScrip, offlineIronScrip, updatedQty, "Executed via deterministic offline ledger");
        }
    }

    public RunVerifyResult verifyConvoyRun(
            DeterministicReplayRecorder recorder,
            String runId,
            String originHubId,
            String destinationHubId,
            int initialCoolingRods) {
        String actualRunId = (runId == null || runId.isBlank()) ? recorder.getRunId() : runId.trim();
        ensureServerLoadoutProvisioned();

        String signedPayload = recorder.toSignedBackendVerifyRequestJson(
                playerId,
                convoyId,
                originHubId != null ? originHubId : "hydro_haven",
                destinationHubId != null ? destinationHubId : "iron_refinery",
                DeterministicReplayRecorder.DEFAULT_SIGNING_SECRET);

        String defaultHash = recorder.computeSha256Hex(recorder.getActions().size(), 120.0f);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/convoy/run/verify"))
                    .timeout(Duration.ofMillis(2500))
                    .header("Content-Type", "application/json")
                    .header("X-Player-ID", playerId)
                    .POST(HttpRequest.BodyPublishers.ofString(signedPayload))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonValue root = new JsonReader().parse(resp.body());
                JsonValue ver = root.get("verification");
                JsonValue led = root.get("ledger_entry");
                boolean isVerified = ver != null && ver.getBoolean("verified", true);
                String stateHash = ver != null ? ver.getString("final_state_hash", defaultHash) : defaultHash;
                long reward = ver != null ? ver.getLong("reward_scrip", 220L) : 220L;
                long balAfter = led != null ? led.getLong("scrip_balance_after", offlineIronScrip + reward) : offlineIronScrip + reward;
                offlineIronScrip = balAfter;
                return new RunVerifyResult(isVerified, true, actualRunId, stateHash, reward, balAfter, resp.body());
            }
        } catch (Exception ignored) {
        }

        boolean validDigest = defaultHash != null && defaultHash.length() == 64;
        long offlineReward = 220L + recorder.getActions().size() * 6L;
        offlineIronScrip += offlineReward;
        return new RunVerifyResult(
                validDigest,
                false,
                actualRunId,
                defaultHash,
                offlineReward,
                offlineIronScrip,
                signedPayload);
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = normalizeBaseUrl(baseUrl); }
    public String getPlayerId() { return playerId; }
    public String getConvoyId() { return convoyId; }
    public long getOfflineIronScrip() { return offlineIronScrip; }
    public void setOfflineIronScrip(long scrip) { this.offlineIronScrip = Math.max(0L, scrip); }
    public Map<String, Integer> getOfflineCargoHold() { return Collections.unmodifiableMap(offlineCargoHold); }
}
