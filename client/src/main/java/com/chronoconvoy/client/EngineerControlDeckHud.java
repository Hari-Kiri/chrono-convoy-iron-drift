package com.chronoconvoy.client;

import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;
import com.chronoconvoy.client.track.SplineTrackSystem;

/**
 * Top-level package alias for {@link com.chronoconvoy.client.ui.EngineerControlDeckHud}.
 */
public class EngineerControlDeckHud extends com.chronoconvoy.client.ui.EngineerControlDeckHud {

    public EngineerControlDeckHud(
            ReactorPowerController reactorController,
            ConvoyPhysicsSystem physicsSystem,
            SplineTrackSystem trackSystem,
            DeterministicReplayRecorder replayRecorder) {
        super(reactorController, physicsSystem, trackSystem, replayRecorder);
    }

    public EngineerControlDeckHud(
            ConvoyConfig config,
            ReactorPowerController reactorController,
            SplineTrackSystem trackSystem,
            DeterministicReplayRecorder replayRecorder) {
        super(config, reactorController, trackSystem, replayRecorder);
    }
}
