package com.chronoconvoy.client.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.config.AssetSupport;
import com.chronoconvoy.client.net.BackendApiClient;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oasis Hub Trading & Convoy Refit Screen between desert sectors (Sprint 2).
 *
 * Implements LibGDX {@link Screen} and supports both interactive OpenGL rendering and headless
 * automated trading, cargo mass physics synchronization, carriage upgrades, and hull repair.
 */
public class OasisHubScreen implements Screen {

    public static final List<String> COMMODITY_IDS = BackendApiClient.CANONICAL_COMMODITY_IDS;

    private final String hubId;
    private final BackendApiClient backendClient;
    private final ConvoyPhysicsSystem physicsSystem;
    private final ReactorPowerController reactorController;
    private final DeterministicReplayRecorder replayRecorder;

    private BackendApiClient.HubMarketView marketView;
    private final Map<String, Integer> cargoInventory = new LinkedHashMap<>();
    private final List<String> equippedCarriageBlueprints = new ArrayList<>(List.of(
        "gatling_turret_car", "shield_dome_car", "cargo_smelter_car"
    ));

    private long playerIronScrip = 2500L;
    private int carriageUpgradeLevel = 1;
    private final Table rootTable;
    private Stage stage;
    private Runnable onDepartToNextSectorCallback;

    public OasisHubScreen(
            String hubId,
            BackendApiClient backendClient,
            ConvoyPhysicsSystem physicsSystem,
            ReactorPowerController reactorController,
            DeterministicReplayRecorder replayRecorder) {
        AssetSupport.ensureHeadlessFilesInitialized();
        this.hubId = (hubId == null || hubId.isBlank()) ? "hydro_haven" : hubId.trim();
        this.backendClient = backendClient != null ? backendClient : new BackendApiClient();
        this.physicsSystem = physicsSystem;
        this.reactorController = reactorController;
        this.replayRecorder = replayRecorder;

        for (String cid : COMMODITY_IDS) {
            cargoInventory.put(cid, 0);
        }

        this.rootTable = new Table();
        this.rootTable.setFillParent(true);
        refreshMarketQuotes();
    }

    public BackendApiClient.HubMarketView refreshMarketQuotes() {
        this.marketView = backendClient.fetchOasisMarket(hubId);
        return this.marketView;
    }

    public BackendApiClient.TradeExecutionResult buyCommodity(String commodityId, int quantity) {
        if (quantity <= 0 || !COMMODITY_IDS.contains(commodityId)) {
            return new BackendApiClient.TradeExecutionResult(
                    false, false, "invalid", commodityId, "BUY", quantity,
                    0L, 0L, playerIronScrip, getCommodityQuantity(commodityId), "Invalid commodity or quantity");
        }
        BackendApiClient.TradeExecutionResult res = backendClient.executeMarketTrade(
                hubId, commodityId, "BUY", quantity, null);
        if (res.isSuccess()) {
            this.playerIronScrip = res.getScripBalanceAfter();
            int updatedQty = cargoInventory.getOrDefault(commodityId, 0) + quantity;
            cargoInventory.put(commodityId, updatedQty);

            if ("cooling_rods".equals(commodityId) && reactorController != null) {
                reactorController.addCoolingRods(quantity);
            }
            recalculateAndApplyConvoyCargoMass();
        }
        return res;
    }

    public BackendApiClient.TradeExecutionResult sellCommodity(String commodityId, int quantity) {
        int currentHeld = cargoInventory.getOrDefault(commodityId, 0);
        if (quantity <= 0 || currentHeld < quantity || !COMMODITY_IDS.contains(commodityId)) {
            return new BackendApiClient.TradeExecutionResult(
                    false, false, "insufficient-cargo", commodityId, "DELIVER", quantity,
                    0L, 0L, playerIronScrip, currentHeld, "Insufficient cargo to sell");
        }
        BackendApiClient.TradeExecutionResult res = backendClient.executeMarketTrade(
                hubId, commodityId, "DELIVER", quantity, null);
        if (res.isSuccess()) {
            this.playerIronScrip = res.getScripBalanceAfter();
            int remaining = Math.max(0, currentHeld - quantity);
            cargoInventory.put(commodityId, remaining);
            recalculateAndApplyConvoyCargoMass();
        }
        return res;
    }

    public boolean upgradeCarriage(int slotIndex, String newBlueprintId, long upgradeCostScrip) {
        if (slotIndex < 0 || slotIndex >= equippedCarriageBlueprints.size()) {
            return false;
        }
        long cost = Math.max(0L, upgradeCostScrip);
        if (playerIronScrip < cost) {
            return false;
        }
        playerIronScrip -= cost;
        backendClient.setOfflineIronScrip(playerIronScrip);
        equippedCarriageBlueprints.set(slotIndex, newBlueprintId);
        carriageUpgradeLevel++;

        int upgradedMaxWatts = 100 + (carriageUpgradeLevel - 1) * 10;
        int upgradedCargoCap = 250 + (carriageUpgradeLevel - 1) * 40;
        int coolingBay = reactorController != null
                ? Math.max(12, reactorController.getCoolingRodsRemaining())
                : 12;
        backendClient.syncConvoyLoadout(upgradedMaxWatts, upgradedCargoCap, coolingBay, equippedCarriageBlueprints);
        return true;
    }

    public boolean repairConvoyHull(long scripCost) {
        if (playerIronScrip < scripCost) {
            return false;
        }
        playerIronScrip -= Math.max(0L, scripCost);
        backendClient.setOfflineIronScrip(playerIronScrip);
        return true;
    }

    public float recalculateAndApplyConvoyCargoMass() {
        float totalCargoKg = 0.0f;
        for (Map.Entry<String, Integer> entry : cargoInventory.entrySet()) {
            float unitMassKg = BackendApiClient.getCanonicalUnitMassKg(entry.getKey());
            totalCargoKg += unitMassKg * Math.max(0, entry.getValue());
        }
        float totalTons = totalCargoKg / 1000.0f;

        if (physicsSystem != null && !physicsSystem.getCarriages().isEmpty()) {
            int count = physicsSystem.getCarriages().size();
            for (int i = 0; i < count; i++) {
                float share = (i == count - 1) ? 0.60f : (0.40f / Math.max(1, count - 1));
                physicsSystem.setCarriageCargoMass(i, totalTons * share);
            }
        }
        return totalTons;
    }

    public void departToNextSector() {
        if (onDepartToNextSectorCallback != null) {
            onDepartToNextSectorCallback.run();
        }
    }

    @Override
    public void show() {
        refreshMarketQuotes();
        if (Gdx.gl != null && Gdx.graphics != null && stage == null) {
            try {
                stage = new Stage();
                stage.addActor(rootTable);
            } catch (Exception ignored) {
                stage = null;
            }
        }
    }

    @Override
    public void render(float delta) {
        if (Gdx.gl != null) {
            Gdx.gl.glClearColor(0.14f, 0.12f, 0.10f, 1.0f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            if (stage != null) {
                stage.act(delta);
                stage.draw();
            }
        }
    }

    @Override
    public void resize(int width, int height) {
        if (stage != null && stage.getViewport() != null) {
            stage.getViewport().update(width, height, true);
        }
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}

    @Override
    public void dispose() {
        if (stage != null) {
            try { stage.dispose(); } catch (Exception ignored) {}
            stage = null;
        }
    }

    public String getHubId() { return hubId; }
    public BackendApiClient.HubMarketView getMarketView() { return marketView; }
    public long getPlayerIronScrip() { return playerIronScrip; }
    public void setPlayerIronScrip(long scrip) {
        this.playerIronScrip = Math.max(0L, scrip);
        this.backendClient.setOfflineIronScrip(this.playerIronScrip);
    }
    public int getCommodityQuantity(String commodityId) {
        return cargoInventory.getOrDefault(commodityId, 0);
    }
    public Map<String, Integer> getCargoInventory() {
        return Collections.unmodifiableMap(cargoInventory);
    }
    public List<String> getEquippedCarriageBlueprints() {
        return Collections.unmodifiableList(equippedCarriageBlueprints);
    }
    public int getCarriageUpgradeLevel() { return carriageUpgradeLevel; }
    public Table getRootTable() { return rootTable; }
    public DeterministicReplayRecorder getReplayRecorder() { return replayRecorder; }
    public void setOnDepartToNextSectorCallback(Runnable callback) {
        this.onDepartToNextSectorCallback = callback;
    }
}
