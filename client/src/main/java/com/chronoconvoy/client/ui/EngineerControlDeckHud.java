package com.chronoconvoy.client.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.chronoconvoy.client.audio.AdaptiveAudioMixer;
import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.config.AssetSupport;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;
import com.chronoconvoy.client.track.SplineTrackSystem;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Scene2D UI Controller for the Engineer's Control Deck HUD (Sprint 1 & 2 CLI-02 / UI-01).
 *
 * Wires @Mochi's client/assets/ui/convoy_hud_skin.json and convoy_hud.atlas into:
 * - Interactive 3-way Reactor Watt Sliders (Shields, Artillery, Thrusters)
 * - Core Temp ProgressBar gauge & overheat lockout indicator
 * - Cargo Tonnage vs. Braking Distance ProgressBars
 * - Track Switcher buttons (LEFT_BRANCH, MAIN_LINE, RIGHT_BRANCH)
 * - Emergency Reactor Cooling Rod button
 *
 * Works seamlessly both in live OpenGL mode (loading TextureAtlas + Skin + Stage) and in
 * headless JUnit / CLI simulation mode without requiring an OpenGL context.
 */
public class EngineerControlDeckHud implements Disposable {

    public static final String SKIN_JSON_PATH = "ui/convoy_hud_skin.json";
    public static final String ATLAS_PATH = "ui/convoy_hud.atlas";

    private final ReactorPowerController reactorController;
    private ConvoyPhysicsSystem physicsSystem;
    private final SplineTrackSystem trackSystem;
    private final DeterministicReplayRecorder replayRecorder;
    private AdaptiveAudioMixer audioMixer;

    private Skin skin;
    private TextureAtlas atlas;
    private Stage stage;

    private final Set<String> verifiedSkinSliderStyles = new LinkedHashSet<>();
    private final Set<String> verifiedSkinProgressBarStyles = new LinkedHashSet<>();
    private final Set<String> verifiedAtlasRegions = new LinkedHashSet<>();

    private final Table rootTable;
    private final Slider shieldsWattSlider;
    private final Slider artilleryWattSlider;
    private final Slider thrustersWattSlider;

    private final ProgressBar coreTempBar;
    private final ProgressBar cargoTonnageBar;
    private final ProgressBar brakingDistanceBar;

    private final Button leftBranchButton;
    private final Button mainLineButton;
    private final Button rightBranchButton;
    private final Button coolingRodButton;

    private boolean suppressEvents = false;
    private boolean disposed = false;

    public EngineerControlDeckHud(
            ReactorPowerController reactorController,
            ConvoyPhysicsSystem physicsSystem,
            SplineTrackSystem trackSystem,
            DeterministicReplayRecorder replayRecorder) {
        this(ConvoyConfig.loadDefault(), reactorController, trackSystem, replayRecorder, physicsSystem, null);
    }

    public EngineerControlDeckHud(
            ConvoyConfig config,
            ReactorPowerController reactorController,
            SplineTrackSystem trackSystem,
            DeterministicReplayRecorder replayRecorder) {
        this(config, reactorController, trackSystem, replayRecorder, null, null);
    }

    public EngineerControlDeckHud(
            ConvoyConfig config,
            ReactorPowerController reactorController,
            SplineTrackSystem trackSystem,
            DeterministicReplayRecorder replayRecorder,
            ConvoyPhysicsSystem physicsSystem,
            AdaptiveAudioMixer audioMixer) {
        AssetSupport.ensureHeadlessFilesInitialized();

        this.reactorController = Objects.requireNonNull(reactorController, "reactorController");
        this.physicsSystem = physicsSystem;
        this.trackSystem = trackSystem;
        this.replayRecorder = replayRecorder;
        this.audioMixer = audioMixer;

        inspectAndLoadSkinAssets();

        this.rootTable = new Table();
        this.rootTable.setFillParent(true);

        Slider.SliderStyle shieldsStyle = resolveSliderStyle("reactor-shields");
        Slider.SliderStyle artilleryStyle = resolveSliderStyle("reactor-artillery");
        Slider.SliderStyle thrustersStyle = resolveSliderStyle("reactor-thrusters");

        this.shieldsWattSlider = new Slider(0.0f, ReactorPowerController.MAX_REACTOR_WATTS, 1.0f, false, shieldsStyle);
        this.artilleryWattSlider = new Slider(0.0f, ReactorPowerController.MAX_REACTOR_WATTS, 1.0f, false, artilleryStyle);
        this.thrustersWattSlider = new Slider(0.0f, ReactorPowerController.MAX_REACTOR_WATTS, 1.0f, false, thrustersStyle);

        ProgressBar.ProgressBarStyle coreTempStyle = resolveProgressBarStyle("core-temp");
        ProgressBar.ProgressBarStyle cargoStyle = resolveProgressBarStyle("cargo-tonnage");
        ProgressBar.ProgressBarStyle brakingStyle = resolveProgressBarStyle("braking-distance");

        float maxHeat = config != null ? config.getMaxHeatCapacity() : 100.0f;
        this.coreTempBar = new ProgressBar(0.0f, maxHeat, 0.5f, false, coreTempStyle);
        this.cargoTonnageBar = new ProgressBar(0.0f, 60.0f, 0.5f, false, cargoStyle);
        this.brakingDistanceBar = new ProgressBar(0.0f, 250.0f, 1.0f, false, brakingStyle);

        Button.ButtonStyle toggleStyle = resolveButtonStyle();
        this.leftBranchButton = new Button(toggleStyle);
        this.mainLineButton = new Button(toggleStyle);
        this.rightBranchButton = new Button(toggleStyle);
        this.coolingRodButton = new Button(toggleStyle);

        syncWidgetsFromModel();
        bindListeners();

        Table reactorPanel = new Table();
        reactorPanel.add(shieldsWattSlider).width(220.0f).pad(4.0f).row();
        reactorPanel.add(artilleryWattSlider).width(220.0f).pad(4.0f).row();
        reactorPanel.add(thrustersWattSlider).width(220.0f).pad(4.0f).row();
        reactorPanel.add(coreTempBar).width(220.0f).pad(6.0f).row();
        reactorPanel.add(coolingRodButton).width(180.0f).pad(4.0f).row();

        Table telemetryPanel = new Table();
        telemetryPanel.add(cargoTonnageBar).width(200.0f).pad(4.0f).row();
        telemetryPanel.add(brakingDistanceBar).width(200.0f).pad(4.0f).row();

        Table trackSwitcherPanel = new Table();
        trackSwitcherPanel.add(leftBranchButton).pad(4.0f);
        trackSwitcherPanel.add(mainLineButton).pad(4.0f);
        trackSwitcherPanel.add(rightBranchButton).pad(4.0f);

        this.rootTable.bottom().pad(12.0f);
        this.rootTable.add(reactorPanel).padRight(16.0f);
        this.rootTable.add(telemetryPanel).padRight(16.0f);
        this.rootTable.add(trackSwitcherPanel);

        if (Gdx.gl != null && Gdx.graphics != null) {
            try {
                this.stage = new Stage();
                this.stage.addActor(this.rootTable);
            } catch (Exception ignored) {
                this.stage = null;
            }
        }
    }

    private void inspectAndLoadSkinAssets() {
        try {
            String skinJson = AssetSupport.readString(SKIN_JSON_PATH);
            JsonValue root = new JsonReader().parse(skinJson);
            JsonValue sliderSection = root.get("com.badlogic.gdx.scenes.scene2d.ui.Slider$SliderStyle");
            if (sliderSection != null) {
                for (JsonValue s = sliderSection.child; s != null; s = s.next) {
                    verifiedSkinSliderStyles.add(s.name);
                }
            }
            JsonValue pbSection = root.get("com.badlogic.gdx.scenes.scene2d.ui.ProgressBar$ProgressBarStyle");
            if (pbSection != null) {
                for (JsonValue p = pbSection.child; p != null; p = p.next) {
                    verifiedSkinProgressBarStyles.add(p.name);
                }
            }

            String atlasText = AssetSupport.readString(ATLAS_PATH);
            for (String line : atlasText.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()
                        && !trimmed.endsWith(".png")
                        && !trimmed.contains(":")) {
                    verifiedAtlasRegions.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to verify UI skin/atlas assets", e);
        }

        if (Gdx.gl != null) {
            try {
                this.atlas = new TextureAtlas(AssetSupport.resolveFileHandle(ATLAS_PATH));
                this.skin = new Skin(AssetSupport.resolveFileHandle(SKIN_JSON_PATH), this.atlas);
            } catch (Exception ignored) {
                this.skin = null;
            }
        }
    }

    private Slider.SliderStyle resolveSliderStyle(String styleName) {
        if (skin != null && skin.has(styleName, Slider.SliderStyle.class)) {
            return skin.get(styleName, Slider.SliderStyle.class);
        }
        Slider.SliderStyle style = new Slider.SliderStyle();
        style.background = new BaseDrawable();
        style.knob = new BaseDrawable();
        return style;
    }

    private ProgressBar.ProgressBarStyle resolveProgressBarStyle(String styleName) {
        if (skin != null && skin.has(styleName, ProgressBar.ProgressBarStyle.class)) {
            return skin.get(styleName, ProgressBar.ProgressBarStyle.class);
        }
        ProgressBar.ProgressBarStyle style = new ProgressBar.ProgressBarStyle();
        style.background = new BaseDrawable();
        style.knobBefore = new BaseDrawable();
        return style;
    }

    private Button.ButtonStyle resolveButtonStyle() {
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.up = new BaseDrawable();
        style.down = new BaseDrawable();
        return style;
    }

    private void bindListeners() {
        ChangeListener wattChangeListener = new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (suppressEvents) {
                    return;
                }
                applySliderValuesToReactor();
            }
        };

        shieldsWattSlider.addListener(wattChangeListener);
        artilleryWattSlider.addListener(wattChangeListener);
        thrustersWattSlider.addListener(wattChangeListener);

        leftBranchButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!suppressEvents) {
                    triggerForkSwitch(ForkDirection.LEFT_BRANCH);
                }
            }
        });
        mainLineButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!suppressEvents) {
                    triggerForkSwitch(ForkDirection.MAIN_LINE);
                }
            }
        });
        rightBranchButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!suppressEvents) {
                    triggerForkSwitch(ForkDirection.RIGHT_BRANCH);
                }
            }
        });
        coolingRodButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!suppressEvents) {
                    triggerEmergencyCoolingRod();
                }
            }
        });
    }

    private void applySliderValuesToReactor() {
        applySliderRouting(
            shieldsWattSlider.getValue(),
            artilleryWattSlider.getValue(),
            thrustersWattSlider.getValue()
        );
    }

    private void applySliderRouting(float rawShields, float rawArtillery, float rawThrusters) {
        reactorController.allocateClampedSliderWatts(rawShields, rawArtillery, rawThrusters);
        if (physicsSystem != null) {
            physicsSystem.setThrusterPowerMultiplier(reactorController.getThrustMultiplier());
        }
        syncWidgetsFromModel();
        if (replayRecorder != null) {
            long tick = physicsSystem != null ? physicsSystem.getCompletedTicks() : 0L;
            replayRecorder.recordWattAllocation(
                tick,
                reactorController.getShieldsWatts(),
                reactorController.getArtilleryWatts(),
                reactorController.getThrustersWatts()
            );
        }
    }

    public void syncSlidersFromModel() {
        syncWidgetsFromModel();
    }

    public void syncWidgetsFromModel() {
        suppressEvents = true;
        try {
            shieldsWattSlider.setValue(reactorController.getShieldsWatts());
            artilleryWattSlider.setValue(reactorController.getArtilleryWatts());
            thrustersWattSlider.setValue(reactorController.getThrustersWatts());
            coreTempBar.setValue(reactorController.getCurrentHeat());

            if (physicsSystem != null) {
                cargoTonnageBar.setValue(physicsSystem.getTotalCargoMassTons());
                float speed = Math.max(8.0f, physicsSystem.getForwardSpeedMps());
                float decel = Math.max(0.5f, physicsSystem.computeInstantaneousBrakingDecelerationMps2());
                float estStopDist = (speed * speed) / (2.0f * decel);
                brakingDistanceBar.setValue(estStopDist);
            }
        } finally {
            suppressEvents = false;
        }
    }

    public void updateTelemetry(ConvoyPhysicsSystem physics) {
        this.physicsSystem = physics;
        syncWidgetsFromModel();
    }

    public void setWattSliders(float shieldsWatts, float artilleryWatts, float thrustersWatts) {
        applySliderRouting(shieldsWatts, artilleryWatts, thrustersWatts);
    }

    public void setSliderAllocations(float shieldsWatts, float artilleryWatts, float thrustersWatts) {
        setWattSliders(shieldsWatts, artilleryWatts, thrustersWatts);
    }

    public void triggerForkSwitch(ForkDirection direction) {
        if (trackSystem != null) {
            trackSystem.switchFork(direction);
        }
        if (audioMixer != null) {
            audioMixer.triggerTrackSwitch();
        }
        if (replayRecorder != null) {
            long tick = physicsSystem != null ? physicsSystem.getCompletedTicks() : 0L;
            replayRecorder.recordForkSwitch(tick, direction);
        }
    }

    public void selectTrackFork(ForkDirection direction) {
        triggerForkSwitch(direction);
    }

    public boolean triggerEmergencyCoolingRod() {
        boolean consumed = reactorController.consumeCoolingRod();
        syncWidgetsFromModel();
        if (consumed) {
            if (physicsSystem != null) {
                physicsSystem.setThrusterPowerMultiplier(reactorController.getThrustMultiplier());
            }
            if (replayRecorder != null) {
                long tick = physicsSystem != null ? physicsSystem.getCompletedTicks() : 0L;
                replayRecorder.recordCoolingRodUsed(tick, reactorController.getCurrentHeat());
            }
        }
        return consumed;
    }

    public void render(float deltaSeconds) {
        syncWidgetsFromModel();
        if (stage != null && Gdx.gl != null) {
            stage.act(deltaSeconds);
            stage.draw();
        }
    }

    public Table getRootTable() { return rootTable; }
    public Stage getStage() { return stage; }

    public Slider getShieldsWattSlider() { return shieldsWattSlider; }
    public Slider getArtilleryWattSlider() { return artilleryWattSlider; }
    public Slider getThrustersWattSlider() { return thrustersWattSlider; }

    public ProgressBar getCoreTempBar() { return coreTempBar; }
    public ProgressBar getCargoTonnageBar() { return cargoTonnageBar; }
    public ProgressBar getBrakingDistanceBar() { return brakingDistanceBar; }

    public Button getLeftBranchButton() { return leftBranchButton; }
    public Button getMainLineButton() { return mainLineButton; }
    public Button getRightBranchButton() { return rightBranchButton; }
    public Button getCoolingRodButton() { return coolingRodButton; }

    public boolean isOverheatLockoutActive() { return reactorController.isOverheated(); }
    public Set<String> getVerifiedSkinSliderStyles() { return Collections.unmodifiableSet(verifiedSkinSliderStyles); }
    public Set<String> getVerifiedSkinProgressBarStyles() { return Collections.unmodifiableSet(verifiedSkinProgressBarStyles); }
    public Set<String> getVerifiedAtlasRegions() { return Collections.unmodifiableSet(verifiedAtlasRegions); }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (stage != null) {
            try { stage.dispose(); } catch (Exception ignored) {}
            stage = null;
        }
        if (skin != null) {
            try { skin.dispose(); } catch (Exception ignored) {}
            skin = null;
        }
        if (atlas != null) {
            try { atlas.dispose(); } catch (Exception ignored) {}
            atlas = null;
        }
    }
}
