package com.chronoconvoy.client;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.chronoconvoy.client.audio.AdaptiveAudioMixer;
import com.chronoconvoy.client.combat.TurretCombatSystem;
import com.chronoconvoy.client.config.AssetSupport;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.net.BackendApiClient;
import com.chronoconvoy.client.render.Convoy3DRenderer;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;
import com.chronoconvoy.client.ui.EngineerControlDeckHud;
import com.chronoconvoy.client.ui.OasisHubScreen;

import java.util.List;

/**
 * Main LibGDX {@link Game} orchestrator for Chrono-Convoy: Iron Drift (v1.0.0 Full Playable Release).
 */
public class ChronoConvoyGame extends Game {

    private final ConvoyConfig config;
    private final long deterministicSeed;
    private final String backendBaseUrl;

    private ChronoConvoySimulationSession simulationSession;
    private Convoy3DRenderer renderer3D;
    private AdaptiveAudioMixer audioMixer;
    private BackendApiClient backendClient;
    private OasisHubScreen oasisHubScreen;
    private ConvoyGameplayScreen gameplayScreen;
    private boolean initialized = false;

    public ChronoConvoyGame() {
        this(ConvoyConfig.loadDefault(), 42069L, BackendApiClient.DEFAULT_BASE_URL);
    }

    public ChronoConvoyGame(String backendBaseUrl) {
        this(ConvoyConfig.loadDefault(), 42069L, backendBaseUrl);
    }

    public ChronoConvoyGame(ConvoyConfig config, long deterministicSeed, String backendBaseUrl) {
        this.config = config != null ? config : ConvoyConfig.loadDefault();
        this.deterministicSeed = deterministicSeed;
        this.backendBaseUrl = (backendBaseUrl == null || backendBaseUrl.isBlank())
                ? BackendApiClient.DEFAULT_BASE_URL
                : backendBaseUrl;
    }

    @Override
    public void create() {
        initializeSystems();
        switchToGameplayScreen();
    }

    public void initializeSystems() {
        if (initialized) {
            return;
        }
        AssetSupport.ensureHeadlessFilesInitialized();
        String runId = "run-seed-" + deterministicSeed;
        this.simulationSession = new ChronoConvoySimulationSession(config, deterministicSeed, runId);
        this.renderer3D = new Convoy3DRenderer(1280, 720);
        this.audioMixer = new AdaptiveAudioMixer();
        this.backendClient = new BackendApiClient(backendBaseUrl);
        this.oasisHubScreen = new OasisHubScreen(
                "hydro_haven",
                backendClient,
                simulationSession.getPhysicsSystem(),
                simulationSession.getReactorController(),
                simulationSession.getReplayRecorder());
        this.oasisHubScreen.setOnDepartToNextSectorCallback(this::switchToGameplayScreen);
        this.gameplayScreen = new ConvoyGameplayScreen();
        this.initialized = true;
    }

    @Override
    public void setScreen(Screen screen) {
        if (Gdx.graphics != null) {
            super.setScreen(screen);
        } else {
            if (this.screen != null) {
                this.screen.hide();
            }
            this.screen = screen;
            if (this.screen != null) {
                this.screen.show();
                this.screen.resize(1600, 900);
            }
        }
    }

    public void switchToGameplayScreen() {
        initializeSystems();
        setScreen(gameplayScreen);
    }

    public void switchToOasisHubScreen() {
        initializeSystems();
        oasisHubScreen.refreshMarketQuotes();
        setScreen(oasisHubScreen);
    }

    public void stepSimulationTick() {
        initializeSystems();
        int prevFired = simulationSession.getCombatSystem().getTotalShotsFired();
        int prevRamCount = simulationSession.getPhysicsSystem().getActiveRammingEvents().size;

        simulationSession.stepFixed();

        float dt = ConvoyConfig.FIXED_TIMESTEP;
        renderer3D.syncWithSimulation(
                simulationSession.getPhysicsSystem(),
                simulationSession.getTrackSystem(),
                simulationSession.getCombatSystem(),
                dt);
        renderer3D.render();

        EngineerControlDeckHud hud = simulationSession.getControlDeckHud();
        hud.updateTelemetry(simulationSession.getPhysicsSystem());
        hud.render(dt);

        int activeRaiders = simulationSession.getCombatSystem().getActiveRaiders().size;
        float combatThreat = Math.min(1.0f, activeRaiders * 0.45f);
        audioMixer.update(
                simulationSession.getPhysicsSystem().getForwardSpeedMps(),
                simulationSession.getReactorController().getCurrentHeat(),
                combatThreat,
                simulationSession.getReactorController().isOverheated(),
                dt);

        if (simulationSession.getCombatSystem().getTotalShotsFired() > prevFired) {
            audioMixer.triggerArtilleryFire();
        }
        if (simulationSession.getPhysicsSystem().getActiveRammingEvents().size > prevRamCount) {
            audioMixer.triggerHeavyRamImpact();
        }
    }

    public BackendApiClient.RunVerifyResult executeFullSectorAndOasisCycle(int totalTicks) {
        initializeSystems();

        backendClient.syncConvoyLoadout(
                100,
                250,
                simulationSession.getReactorController().getCoolingRodsRemaining(),
                List.of("gatling_turret_car", "shield_dome_car", "cargo_smelter_car"));
        oasisHubScreen.refreshMarketQuotes();

        oasisHubScreen.buyCommodity("purified_water", 6);
        oasisHubScreen.buyCommodity("crude_promethium", 4);
        oasisHubScreen.buyCommodity("cooling_rods", 1);

        switchToGameplayScreen();
        simulationSession.getCombatSystem().spawnRaider(
                "raider-buggy-01", TurretCombatSystem.RaiderArchetype.RAMMER_BUGGY, 34.0f, 3.0f, -4.0f, 0.0f);
        simulationSession.getCombatSystem().spawnRaider(
                "raider-skiff-02", TurretCombatSystem.RaiderArchetype.MORTAR_SKIFF, 52.0f, -4.0f, 0.0f, 0.0f);

        int ticks = Math.max(1, totalTicks);
        int oneThird = Math.max(1, ticks / 3);
        int twoThirds = Math.max(2, (ticks * 2) / 3);

        for (int t = 0; t < ticks; t++) {
            if (t == 0) {
                simulationSession.getControlDeckHud().setWattSliders(25.0f, 40.0f, 35.0f);
            } else if (t == oneThird) {
                simulationSession.getControlDeckHud().triggerForkSwitch(ForkDirection.LEFT_BRANCH);
                audioMixer.triggerTrackSwitch();
                simulationSession.getControlDeckHud().setWattSliders(20.0f, 45.0f, 35.0f);
            } else if (t == twoThirds) {
                simulationSession.getControlDeckHud().triggerForkSwitch(ForkDirection.RIGHT_BRANCH);
                audioMixer.triggerTrackSwitch();
                if (simulationSession.getReactorController().getCurrentHeat() > 20.0f) {
                    simulationSession.getControlDeckHud().triggerEmergencyCoolingRod();
                }
            }
            stepSimulationTick();
        }

        switchToOasisHubScreen();
        oasisHubScreen.sellCommodity("purified_water", 3);
        oasisHubScreen.upgradeCarriage(0, "plasma_lancer_car", 150L);

        String runId = "run-seed-" + deterministicSeed + "-ticks-" + ticks;
        return backendClient.verifyConvoyRun(
                simulationSession.getReplayRecorder(),
                runId,
                "hydro_haven",
                "iron_refinery",
                config.getInitialCoolingRods());
    }

    public final class ConvoyGameplayScreen implements Screen {
        @Override public void show() {}
        @Override public void render(float delta) { stepSimulationTick(); }
        @Override public void resize(int width, int height) {
            if (renderer3D != null) {
                renderer3D.resize(width, height);
            }
        }
        @Override public void pause() {}
        @Override public void resume() {}
        @Override public void hide() {}
        @Override public void dispose() {}
    }

    public ConvoyConfig getConfig() { return config; }
    public ChronoConvoySimulationSession getSimulationSession() { return simulationSession; }
    public Convoy3DRenderer getRenderer3D() { return renderer3D; }
    public AdaptiveAudioMixer getAudioMixer() { return audioMixer; }
    public BackendApiClient getBackendClient() { return backendClient; }
    public OasisHubScreen getOasisHubScreen() { return oasisHubScreen; }
    public ConvoyGameplayScreen getGameplayScreen() { return gameplayScreen; }

    @Override
    public void dispose() {
        super.dispose();
        if (oasisHubScreen != null) {
            oasisHubScreen.dispose();
        }
        if (renderer3D != null) {
            renderer3D.dispose();
        }
        if (audioMixer != null) {
            audioMixer.dispose();
        }
        if (simulationSession != null) {
            simulationSession.getControlDeckHud().dispose();
            simulationSession.close();
        }
    }
}
