package com.chronoconvoy.client.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;
import com.chronoconvoy.client.track.SplineTrackSystem;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;

import java.util.Objects;

/**
 * [CLI-02] Scene2D Diegetic Engineer Control Deck HUD.
 * Binds Scene2D Table & Slider actors (Shields, Artillery, Thrusters), Track Fork Switch controls,
 * and Emergency Cooling Rod Vent controls directly to the live ReactorPowerController,
 * ConvoyPhysicsSystem, SplineTrackSystem, and DeterministicReplayRecorder.
 *
 * Uses headless-compatible Scene2D SliderStyle drawables by default so unit tests and headless
 * simulations can drive the exact same Scene2D Slider actors without requiring an OpenGL context.
 */
public final class EngineerControlDeckHud {

    static {
        ensureHeadlessGdxFiles();
    }

    private static void ensureHeadlessGdxFiles() {
        if (Gdx.files == null) {
            Gdx.files = new HeadlessFiles();
        }
    }

    private final ReactorPowerController reactorController;
    private final ConvoyPhysicsSystem physicsSystem;
    private final SplineTrackSystem trackSystem;
    private final DeterministicReplayRecorder replayRecorder;

    private final Table rootTable;
    private final Slider shieldsWattSlider;
    private final Slider artilleryWattSlider;
    private final Slider thrustersWattSlider;
    private boolean updatingSlidersInternally = false;

    public EngineerControlDeckHud(
        ReactorPowerController reactorController,
        ConvoyPhysicsSystem physicsSystem,
        SplineTrackSystem trackSystem,
        DeterministicReplayRecorder replayRecorder
    ) {
        ensureHeadlessGdxFiles();
        this.reactorController = Objects.requireNonNull(reactorController, "reactorController");
        this.physicsSystem = Objects.requireNonNull(physicsSystem, "physicsSystem");
        this.trackSystem = Objects.requireNonNull(trackSystem, "trackSystem");
        this.replayRecorder = Objects.requireNonNull(replayRecorder, "replayRecorder");

        Slider.SliderStyle sliderStyle = createHeadlessSafeSliderStyle();
        this.shieldsWattSlider = new Slider(0.0f, 100.0f, 1.0f, false, sliderStyle);
        this.artilleryWattSlider = new Slider(0.0f, 100.0f, 1.0f, false, sliderStyle);
        this.thrustersWattSlider = new Slider(0.0f, 100.0f, 1.0f, false, sliderStyle);

        syncSlidersFromController();

        ChangeListener sliderListener = new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (updatingSlidersInternally) {
                    return;
                }
                applySliderRouting(
                    shieldsWattSlider.getValue(),
                    artilleryWattSlider.getValue(),
                    thrustersWattSlider.getValue()
                );
            }
        };

        this.shieldsWattSlider.addListener(sliderListener);
        this.artilleryWattSlider.addListener(sliderListener);
        this.thrustersWattSlider.addListener(sliderListener);

        this.rootTable = new Table();
        this.rootTable.setFillParent(true);
        this.rootTable.add(shieldsWattSlider).width(220.0f).pad(6.0f).row();
        this.rootTable.add(artilleryWattSlider).width(220.0f).pad(6.0f).row();
        this.rootTable.add(thrustersWattSlider).width(220.0f).pad(6.0f).row();
    }

    private static Slider.SliderStyle createHeadlessSafeSliderStyle() {
        BaseDrawable background = new BaseDrawable();
        background.setMinWidth(200.0f);
        background.setMinHeight(12.0f);
        BaseDrawable knob = new BaseDrawable();
        knob.setMinWidth(16.0f);
        knob.setMinHeight(20.0f);
        return new Slider.SliderStyle(background, knob);
    }

    private void syncSlidersFromController() {
        updatingSlidersInternally = true;
        try {
            shieldsWattSlider.setValue(reactorController.getShieldsWatts());
            artilleryWattSlider.setValue(reactorController.getArtilleryWatts());
            thrustersWattSlider.setValue(reactorController.getThrustersWatts());
        } finally {
            updatingSlidersInternally = false;
        }
    }

    /**
     * Routes Reactor Watts via the Scene2D Engineer Control Deck sliders, updating live thrust,
     * turret fire rate, and shield dome capacity, and recording the action in the deterministic log.
     */
    public void setWattSliders(float shieldsWatts, float artilleryWatts, float thrustersWatts) {
        applySliderRouting(shieldsWatts, artilleryWatts, thrustersWatts);
    }

    private void applySliderRouting(float rawShields, float rawArtillery, float rawThrusters) {
        reactorController.allocateClampedSliderWatts(rawShields, rawArtillery, rawThrusters);
        physicsSystem.setThrusterPowerMultiplier(reactorController.getThrustMultiplier());
        syncSlidersFromController();
        replayRecorder.recordWattAllocation(
            physicsSystem.getCompletedTicks(),
            reactorController.getShieldsWatts(),
            reactorController.getArtilleryWatts(),
            reactorController.getThrustersWatts()
        );
    }

    public void triggerForkSwitch(ForkDirection direction) {
        trackSystem.switchFork(direction);
        replayRecorder.recordForkSwitch(physicsSystem.getCompletedTicks(), direction);
    }

    public boolean triggerEmergencyCoolingRod() {
        boolean consumed = reactorController.consumeCoolingRod();
        if (consumed) {
            physicsSystem.setThrusterPowerMultiplier(reactorController.getThrustMultiplier());
            replayRecorder.recordCoolingRodUsed(physicsSystem.getCompletedTicks(), reactorController.getCurrentHeat());
        }
        return consumed;
    }

    public Table getRootTable() { return rootTable; }
    public Slider getShieldsWattSlider() { return shieldsWattSlider; }
    public Slider getArtilleryWattSlider() { return artilleryWattSlider; }
    public Slider getThrustersWattSlider() { return thrustersWattSlider; }
}
