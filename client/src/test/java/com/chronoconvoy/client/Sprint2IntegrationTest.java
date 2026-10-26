package com.chronoconvoy.client;

import com.badlogic.gdx.math.Vector3;
import com.chronoconvoy.client.audio.AdaptiveAudioMixer;
import com.chronoconvoy.client.combat.TurretCombatSystem;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.net.BackendApiClient;
import com.chronoconvoy.client.render.Convoy3DRenderer;
import com.chronoconvoy.client.ui.EngineerControlDeckHud;
import com.chronoconvoy.client.ui.OasisHubScreen;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 2 Full Playable Release Integration Tests (v1.0.0).
 */
class Sprint2IntegrationTest {

    @Test
    @DisplayName("1. Convoy3DRenderer loads all 5 @Onigiri .obj meshes and syncs 3D transforms with Box2D physics")
    void testConvoy3DRendererMeshesAndTransformSync() {
        ConvoyConfig config = ConvoyConfig.loadDefault();
        try (ChronoConvoySimulationSession session = new ChronoConvoySimulationSession(config, 42069L, "test-3d-run")) {
            Convoy3DRenderer renderer = new Convoy3DRenderer(1280, 720);
            try {
                Map<String, Convoy3DRenderer.ObjMeshMetadata> allMeta = renderer.getAllMeshMetadata();
                assertEquals(5, allMeta.size(), "All 5 required vehicle .obj meshes must be loaded");

                for (String meshFile : Convoy3DRenderer.REQUIRED_VEHICLE_MESHES) {
                    Convoy3DRenderer.ObjMeshMetadata meta = renderer.getLoadedMeshMetadata(meshFile);
                    assertNotNull(meta, "Metadata missing for " + meshFile);
                    assertTrue(meta.getVertexCount() > 100, "Expected >100 vertices for " + meshFile);
                    assertTrue(meta.getTriangleCount() > 200 && meta.getTriangleCount() <= 3500,
                            "Mesh " + meshFile + " must be within the 3,500 triangle budget");
                    assertTrue(meta.getObjectNodeNames().contains("chassis"),
                            "Mesh " + meshFile + " must contain root chassis object node");
                }

                assertEquals(1572, renderer.getLoadedMeshMetadata("locomotive.obj").getTriangleCount());
                assertEquals(1044, renderer.getLoadedMeshMetadata("carriage_artillery.obj").getTriangleCount());
                assertEquals(936, renderer.getLoadedMeshMetadata("carriage_cargo.obj").getTriangleCount());
                assertEquals(784, renderer.getLoadedMeshMetadata("raider_buggy.obj").getTriangleCount());
                assertEquals(1210, renderer.getLoadedMeshMetadata("raider_harpoon_rig.obj").getTriangleCount());

                session.getCombatSystem().spawnRaider("buggy-1", TurretCombatSystem.RaiderArchetype.RAMMER_BUGGY, 32.0f, 3.0f, -4.0f, 0.0f);
                session.getCombatSystem().spawnRaider("skiff-1", TurretCombatSystem.RaiderArchetype.MORTAR_SKIFF, 48.0f, -3.5f, 0.0f, 0.0f);

                for (int i = 0; i < 60; i++) {
                    session.stepFixed();
                    renderer.syncWithSimulation(
                            session.getPhysicsSystem(),
                            session.getTrackSystem(),
                            session.getCombatSystem(),
                            ConvoyConfig.FIXED_TIMESTEP);
                    renderer.render();
                }

                Vector3 locoTranslation = new Vector3();
                renderer.getLocomotiveTransform().getTranslation(locoTranslation);
                assertTrue(locoTranslation.x > 1.0f,
                        "Locomotive 3D ModelInstance X translation must advance with Box2D body");
                assertEquals(3, renderer.getCarriageInstances().size(), "Must sync 3 articulated carriages");
                assertFalse(renderer.getRaiderInstances().isEmpty(), "Active raiders must have 3D ModelInstances");
                assertTrue(renderer.getCumulativeWheelRotationDeg() > 0.0f, "Wheels must rotate with forward speed");
                assertEquals(60, renderer.getRenderedFrameCount());
            } finally {
                renderer.dispose();
            }
        }
    }

    @Test
    @DisplayName("2. EngineerControlDeckHud & OasisHubScreen verify skin/atlas, trade all 5 commodities, and update Box2D cargo mass")
    void testHudSkinAndOasisHubTradingScreen() {
        ConvoyConfig config = ConvoyConfig.loadDefault();
        try (ChronoConvoySimulationSession session = new ChronoConvoySimulationSession(config, 42069L, "test-hud-oasis")) {
            EngineerControlDeckHud hud = session.getControlDeckHud();
            assertTrue(hud.getVerifiedSkinSliderStyles().containsAll(
                    List.of("reactor-shields", "reactor-artillery", "reactor-thrusters")),
                    "HUD must verify @Mochi's reactor slider styles in convoy_hud_skin.json");
            assertTrue(hud.getVerifiedSkinProgressBarStyles().containsAll(
                    List.of("core-temp", "core-temp-critical", "cargo-tonnage", "braking-distance")),
                    "HUD must verify @Mochi's progress bar styles in convoy_hud_skin.json");
            assertFalse(hud.getVerifiedAtlasRegions().isEmpty(), "Atlas regions must be parsed from convoy_hud.atlas");

            BackendApiClient offlineClient = new BackendApiClient("http://127.0.0.1:59999");
            OasisHubScreen oasisScreen = new OasisHubScreen(
                    "hydro_haven",
                    offlineClient,
                    session.getPhysicsSystem(),
                    session.getReactorController(),
                    session.getReplayRecorder());

            float baseMassTons = session.getPhysicsSystem().getTotalConvoyMassTons();
            long initialScrip = oasisScreen.getPlayerIronScrip();
            int initialRods = session.getReactorController().getCoolingRodsRemaining();

            for (String commodityId : OasisHubScreen.COMMODITY_IDS) {
                BackendApiClient.TradeExecutionResult buyRes = oasisScreen.buyCommodity(commodityId, 2);
                assertTrue(buyRes.isSuccess(), "Buying " + commodityId + " must succeed");
                assertEquals(2, oasisScreen.getCommodityQuantity(commodityId));
            }

            assertTrue(oasisScreen.getPlayerIronScrip() < initialScrip,
                    "Buying commodities must deduct Iron Scrip");
            assertTrue(session.getPhysicsSystem().getTotalConvoyMassTons() > baseMassTons,
                    "Buying commodities must increase Box2D convoy cargo mass");
            assertEquals(initialRods + 2, session.getReactorController().getCoolingRodsRemaining(),
                    "Buying cooling_rods commodity must replenish ReactorPowerController cooling rod bay");

            hud.updateTelemetry(session.getPhysicsSystem());
            assertTrue(hud.getCargoTonnageBar().getValue() > 0.0f,
                    "HUD cargo tonnage ProgressBar must reflect loaded commodity mass");

            long scripBeforeSell = oasisScreen.getPlayerIronScrip();
            float massBeforeSell = session.getPhysicsSystem().getTotalConvoyMassTons();
            BackendApiClient.TradeExecutionResult sellRes = oasisScreen.sellCommodity("crude_promethium", 1);
            assertTrue(sellRes.isSuccess(), "Selling held commodity must succeed");
            assertTrue(oasisScreen.getPlayerIronScrip() > scripBeforeSell, "Selling cargo must credit Iron Scrip");
            assertTrue(session.getPhysicsSystem().getTotalConvoyMassTons() < massBeforeSell,
                    "Selling cargo must reduce Box2D convoy cargo mass");

            assertTrue(oasisScreen.upgradeCarriage(0, "plasma_lancer_car", 100L));
            assertEquals("plasma_lancer_car", oasisScreen.getEquippedCarriageBlueprints().get(0));
            oasisScreen.dispose();
        }
    }

    @Test
    @DisplayName("3. AdaptiveAudioMixer loads adaptive_audio_cues.json, 4 WAV stems, and 5 SFX and crossfades by speed & heat")
    void testAdaptiveAudioMixerStemsAndSfx() {
        AdaptiveAudioMixer mixer = new AdaptiveAudioMixer();
        try {
            assertEquals("chrono-convoy-adaptive-audio-v1", mixer.getSchemaId());
            assertEquals(120, mixer.getTempoBpm());
            assertEquals(4, mixer.getStems().size(), "Must load all 4 vertical loop stems");
            assertEquals(5, mixer.getSfxCues().size(), "Must load all 5 combat SFX cues");

            for (AdaptiveAudioMixer.StemState stem : mixer.getStems().values()) {
                assertEquals(44100, stem.getSampleRateHz(), "Stem WAV must be 44.1kHz");
                assertEquals(16, stem.getBitsPerSample(), "Stem WAV must be 16-bit PCM");
                assertTrue(stem.getDataSizeBytes() > 100_000, "Stem WAV must contain full audio payload");
            }

            mixer.update(0.0f, 10.0f, 0.0f, false, 1.0f);
            assertTrue(mixer.getStemVolume(AdaptiveAudioMixer.STEM_DESERT_IDLE) > 0.8f);
            assertEquals(0.0f, mixer.getStemVolume(AdaptiveAudioMixer.STEM_LOCOMOTIVE_CHUG), 1e-4f);
            assertEquals(0.0f, mixer.getStemVolume(AdaptiveAudioMixer.STEM_RAIDER_PERCUSSION), 1e-4f);
            assertEquals(0.0f, mixer.getStemVolume(AdaptiveAudioMixer.STEM_OVERDRIVE_SYNTH), 1e-4f);

            mixer.update(28.0f, 88.0f, 0.85f, true, 2.0f);
            assertTrue(mixer.getStemVolume(AdaptiveAudioMixer.STEM_LOCOMOTIVE_CHUG) > 0.7f,
                    "Locomotive chug stem must fade in at cruising speed");
            assertTrue(mixer.getStem(AdaptiveAudioMixer.STEM_LOCOMOTIVE_CHUG).getCurrentPitch() > 1.1f,
                    "Locomotive chug pitch must scale up with speed");
            assertTrue(mixer.getStemVolume(AdaptiveAudioMixer.STEM_RAIDER_PERCUSSION) > 0.7f,
                    "Raider percussion stem must fade in during combat");
            assertTrue(mixer.getStemVolume(AdaptiveAudioMixer.STEM_OVERDRIVE_SYNTH) > 0.7f,
                    "Overdrive synth stem must fade in at high reactor heat");
            assertEquals(1, mixer.getSfxCue(AdaptiveAudioMixer.SFX_REACTOR_ALARM).getTriggerCount(),
                    "Reactor alarm SFX must auto-trigger when core heat >= 75%");

            assertTrue(mixer.triggerArtilleryFire(), "First artillery fire SFX must trigger");
            assertFalse(mixer.triggerArtilleryFire(), "Immediate duplicate artillery fire within 180ms cooldown must be suppressed");
            mixer.advanceClockMs(200L);
            assertTrue(mixer.triggerArtilleryFire(), "Artillery fire after cooldown window must trigger");
        } finally {
            mixer.dispose();
        }
    }

    @Test
    @DisplayName("4. BackendApiClient executes live HTTP market/trade/loadout/verify calls and offline fallback")
    void testBackendApiClientLiveAndOffline() throws Exception {
        HttpServer mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockServer.getAddress().getPort();

        mockServer.createContext("/api/v1/oasis/market/hydro_haven", exchange -> {
            String json = "{\"hub_id\":\"hydro_haven\",\"name\":\"Hydro-Haven Aquifer Bastion\",\"commodities\":{"
                    + "\"purified_water\":{\"commodity_id\":\"purified_water\",\"name\":\"Purified Water\",\"supply\":1200,\"demand\":650,\"bid_price_scrip\":38,\"ask_price_scrip\":42,\"mid_price\":40.0},"
                    + "\"scrap_alloy\":{\"commodity_id\":\"scrap_alloy\",\"name\":\"Scrap Alloy\",\"supply\":800,\"demand\":780,\"bid_price_scrip\":53,\"ask_price_scrip\":57,\"mid_price\":55.0},"
                    + "\"crude_promethium\":{\"commodity_id\":\"crude_promethium\",\"name\":\"Crude Promethium\",\"supply\":450,\"demand\":520,\"bid_price_scrip\":115,\"ask_price_scrip\":125,\"mid_price\":120.0},"
                    + "\"reactor_cells\":{\"commodity_id\":\"reactor_cells\",\"name\":\"Reactor Cells\",\"supply\":240,\"demand\":310,\"bid_price_scrip\":178,\"ask_price_scrip\":192,\"mid_price\":185.0},"
                    + "\"cooling_rods\":{\"commodity_id\":\"cooling_rods\",\"name\":\"Reactor Cooling Rods\",\"supply\":520,\"demand\":500,\"bid_price_scrip\":91,\"ask_price_scrip\":99,\"mid_price\":95.0}"
                    + "}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        mockServer.createContext("/api/v1/convoy/loadout", exchange -> {
            String json = "{\"convoy_id\":\"convoy-iron-drift-01\",\"player_id\":\"player-chronoconvoy-01\","
                    + "\"locomotive_class\":\"iron_drifter_mk1\",\"max_reactor_watts\":100,"
                    + "\"max_cargo_capacity\":250,\"cooling_rod_bay\":12,"
                    + "\"wallet\":{\"iron_scrip\":2500,\"chrono_flux\":100}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        mockServer.createContext("/api/v1/oasis/market/trade", exchange -> {
            String json = "{\"ledger_entry\":{\"unit_price_scrip\":42,\"scrip_delta\":-210,"
                    + "\"scrip_balance_after\":2290,\"cargo_qty_after\":5}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        mockServer.createContext("/api/v1/convoy/run/verify", exchange -> {
            String json = "{\"verification\":{\"verified\":true,\"final_state_hash\":\"abc123def456\",\"reward_scrip\":310},"
                    + "\"ledger_entry\":{\"scrip_balance_after\":2600}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        mockServer.start();
        try {
            BackendApiClient liveClient = new BackendApiClient("http://127.0.0.1:" + port);
            BackendApiClient.HubMarketView market = liveClient.fetchOasisMarket("hydro_haven");
            assertTrue(market.isFromLiveBackend(), "Market view must be fetched from live HTTP endpoint");
            assertEquals(5, market.getQuotes().size());

            BackendApiClient.ConvoyLoadoutState loadout = liveClient.fetchConvoyLoadout();
            assertTrue(loadout.isFromLiveBackend());
            assertEquals(2500L, loadout.getWalletIronScrip());

            BackendApiClient.TradeExecutionResult trade = liveClient.executeMarketTrade(
                    "hydro_haven", "purified_water", "BUY", 5, "idem-live-1");
            assertTrue(trade.isSuccess());
            assertTrue(trade.isLiveBackendUsed());
            assertEquals(2290L, trade.getScripBalanceAfter());

            try (ChronoConvoySimulationSession session = new ChronoConvoySimulationSession(ConvoyConfig.loadDefault(), 42069L, "run-live-1")) {
                for (int i = 0; i < 30; i++) {
                    session.stepFixed();
                }
                BackendApiClient.RunVerifyResult verify = liveClient.verifyConvoyRun(
                        session.getReplayRecorder(), "run-live-1", "hydro_haven", "iron_refinery", 3);
                assertTrue(verify.isVerified());
                assertTrue(verify.isLiveBackendUsed());
                assertEquals(2600L, verify.getScripBalanceAfter());
            }
        } finally {
            mockServer.stop(0);
        }
    }

    @Test
    @DisplayName("5. DesktopLauncher --headless --ticks=300 executes full simulation, Oasis trade, and replay verification cleanly")
    void testDesktopLauncherHeadless300Ticks() {
        int exitCode = DesktopLauncher.runWithArgs(new String[]{
            "--headless",
            "--ticks=300",
            "--seed=42069"
        });
        assertEquals(0, exitCode, "DesktopLauncher --headless --ticks=300 must exit with code 0");
    }
}
