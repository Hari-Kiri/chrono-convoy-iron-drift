package com.chronoconvoy.client;

import com.badlogic.gdx.utils.Disposable;
import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.combat.TurretCombatSystem;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder.ReplayAction;
import com.chronoconvoy.client.track.SplineTrackSystem;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;
import com.chronoconvoy.client.ui.EngineerControlDeckHud;

import java.util.List;
import java.util.Objects;

/**
 * Integrated headless-compatible simulation session combining CLI-01 (SplineTrackSystem +
 * ConvoyPhysicsSystem) and CLI-02 (ReactorPowerController + TurretCombatSystem +
 * EngineerControlDeckHud + DeterministicReplayRecorder).
 */
public final class ChronoConvoySimulationSession implements Disposable, AutoCloseable {

    private final ConvoyConfig config;
    private final long seed;
    private final SplineTrackSystem trackSystem;
    private final ConvoyPhysicsSystem physicsSystem;
    private final ReactorPowerController reactorController;
    private final TurretCombatSystem combatSystem;
    private final DeterministicReplayRecorder replayRecorder;
    private final EngineerControlDeckHud controlDeckHud;

    public ChronoConvoySimulationSession(ConvoyConfig config, long seed, String runId) {
        this.config = Objects.requireNonNull(config, "config");
        this.seed = seed;
        this.trackSystem = new SplineTrackSystem(config, seed);
        this.physicsSystem = new ConvoyPhysicsSystem(config, trackSystem);
        this.reactorController = new ReactorPowerController(config);
        this.combatSystem = new TurretCombatSystem(config, reactorController);
        this.replayRecorder = new DeterministicReplayRecorder(seed, runId);
        this.controlDeckHud = new EngineerControlDeckHud(
            reactorController,
            physicsSystem,
            trackSystem,
            replayRecorder
        );
        this.physicsSystem.setThrusterPowerMultiplier(this.reactorController.getThrustMultiplier());
    }

    /**
     * Steps the entire convoy simulation by one deterministic 1/60s tick.
     */
    public void stepFixed() {
        float dt = ConvoyConfig.FIXED_TIMESTEP;
        reactorController.update(dt);
        physicsSystem.setThrusterPowerMultiplier(reactorController.getThrustMultiplier());
        physicsSystem.stepFixed();
        combatSystem.update(
            dt,
            physicsSystem.getLocomotiveBody().getPosition(),
            physicsSystem.getLocomotiveBody().getLinearVelocity()
        );
        replayRecorder.recordTickState(
            physicsSystem.getCompletedTicks(),
            physicsSystem,
            reactorController,
            combatSystem
        );
    }

    /**
     * Executes a recorded list of ReplayActions at their exact tick timestamps over totalTicks.
     */
    public void executeReplaySchedule(List<ReplayAction> scheduledActions, long totalTicks) {
        int actionCursor = 0;
        for (long t = 0; t < totalTicks; t++) {
            while (actionCursor < scheduledActions.size() && scheduledActions.get(actionCursor).tick() == t) {
                ReplayAction action = scheduledActions.get(actionCursor++);
                switch (action.type()) {
                    case ALLOCATE_WATTS -> controlDeckHud.setWattSliders(action.param1(), action.param2(), action.param3());
                    case SWITCH_FORK -> {
                        int ord = Math.round(action.param1());
                        ForkDirection[] dirs = ForkDirection.values();
                        ForkDirection dir = dirs[Math.max(0, Math.min(dirs.length - 1, ord))];
                        controlDeckHud.triggerForkSwitch(dir);
                    }
                    case CONSUME_COOLING_ROD -> controlDeckHud.triggerEmergencyCoolingRod();
                    case SET_THROTTLE_BRAKE -> {
                        physicsSystem.setThrottleInput(action.param1());
                        physicsSystem.setBrakeInput(action.param2());
                        replayRecorder.recordThrottleAndBrake(t, action.param1(), action.param2());
                    }
                }
            }
            stepFixed();
        }
    }

    public ConvoyConfig getConfig() { return config; }
    public long getSeed() { return seed; }
    public SplineTrackSystem getTrackSystem() { return trackSystem; }
    public ConvoyPhysicsSystem getPhysicsSystem() { return physicsSystem; }
    public ReactorPowerController getReactorController() { return reactorController; }
    public TurretCombatSystem getCombatSystem() { return combatSystem; }
    public DeterministicReplayRecorder getReplayRecorder() { return replayRecorder; }
    public EngineerControlDeckHud getControlDeckHud() { return controlDeckHud; }

    @Override
    public void dispose() {
        physicsSystem.dispose();
    }

    @Override
    public void close() {
        dispose();
    }
}
