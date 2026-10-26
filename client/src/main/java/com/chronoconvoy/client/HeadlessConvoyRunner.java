package com.chronoconvoy.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.chronoconvoy.client.combat.TurretCombatSystem.RaiderArchetype;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.track.SplineTrackSystem.ForkDirection;

/**
 * Headless LibGDX launcher for Chrono-Convoy: Iron Drift vertical slice verification.
 */
public final class HeadlessConvoyRunner extends ApplicationAdapter {

    public static void main(String[] args) {
        HeadlessApplicationConfiguration headlessConfig = new HeadlessApplicationConfiguration();
        headlessConfig.updatesPerSecond = 60;
        ConvoyConfig config = ConvoyConfig.loadDefault();
        try (ChronoConvoySimulationSession session = new ChronoConvoySimulationSession(config, 20261026L, "vertical-slice-01")) {
            new HeadlessApplication(new HeadlessConvoyRunner(), headlessConfig);
            session.getCombatSystem().spawnRaider("buggy-1", RaiderArchetype.RAMMER_BUGGY, 35.0f, 2.0f, -12.0f, 0.0f);
            session.getCombatSystem().spawnRaider("skiff-1", RaiderArchetype.MORTAR_SKIFF, 60.0f, -5.0f, 5.0f, 0.0f);
            for (int tick = 0; tick < 300; tick++) {
                if (tick == 60) {
                    session.getControlDeckHud().setWattSliders(25.0f, 45.0f, 30.0f);
                } else if (tick == 120) {
                    session.getControlDeckHud().triggerForkSwitch(ForkDirection.LEFT_BRANCH);
                }
                session.stepFixed();
            }
            System.out.println(session.getReplayRecorder().toVerifyJsonPayload(
                session.getPhysicsSystem().getCompletedTicks(),
                session.getPhysicsSystem().getDistanceAlongTrackMeters(),
                session.getReactorController().getCoolingRodsConsumed()
            ));
        }
    }
}
