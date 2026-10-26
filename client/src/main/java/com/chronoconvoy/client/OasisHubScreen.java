package com.chronoconvoy.client;

import com.chronoconvoy.client.combat.ReactorPowerController;
import com.chronoconvoy.client.net.BackendApiClient;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.replay.DeterministicReplayRecorder;

/**
 * Top-level package alias for {@link com.chronoconvoy.client.ui.OasisHubScreen}.
 */
public class OasisHubScreen extends com.chronoconvoy.client.ui.OasisHubScreen {

    public OasisHubScreen(
            String hubId,
            BackendApiClient backendClient,
            ConvoyPhysicsSystem physicsSystem,
            ReactorPowerController reactorController,
            DeterministicReplayRecorder replayRecorder) {
        super(hubId, backendClient, physicsSystem, reactorController, replayRecorder);
    }
}
