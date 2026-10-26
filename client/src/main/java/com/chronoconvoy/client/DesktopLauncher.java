package com.chronoconvoy.client;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.chronoconvoy.client.config.ConvoyConfig;
import com.chronoconvoy.client.net.BackendApiClient;
import com.chronoconvoy.client.render.Convoy3DRenderer;

import java.awt.GraphicsEnvironment;
import java.util.Locale;

/**
 * Playable Desktop (LWJGL3) & Headless Simulation Launcher for Chrono-Convoy: Iron Drift (v1.0.0).
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        int code = runWithArgs(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int runWithArgs(String[] args) {
        boolean forceHeadless = false;
        int ticks = 300;
        long seed = 42069L;
        String backendUrl = BackendApiClient.DEFAULT_BASE_URL;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) {
                    continue;
                }
                if ("--headless".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                    forceHeadless = true;
                } else if (arg.startsWith("--ticks=")) {
                    ticks = parsePositiveInt(arg.substring("--ticks=".length()), ticks);
                } else if ("--ticks".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    ticks = parsePositiveInt(args[++i], ticks);
                } else if (arg.startsWith("--backend-url=")) {
                    backendUrl = arg.substring("--backend-url=".length()).trim();
                } else if ("--backend-url".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    backendUrl = args[++i].trim();
                } else if (arg.startsWith("--seed=")) {
                    seed = parseLong(arg.substring("--seed=".length()), seed);
                } else if ("--seed".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    seed = parseLong(args[++i], seed);
                }
            }
        }

        if (!forceHeadless && isDisplayAvailable()) {
            try {
                Lwjgl3ApplicationConfiguration lwjglConfig = new Lwjgl3ApplicationConfiguration();
                lwjglConfig.setTitle("Chrono-Convoy: Iron Drift (v1.0.0)");
                lwjglConfig.setWindowedMode(1280, 720);
                lwjglConfig.setForegroundFPS(60);
                lwjglConfig.useVsync(true);
                new Lwjgl3Application(
                        new ChronoConvoyGame(ConvoyConfig.loadDefault(), seed, backendUrl),
                        lwjglConfig);
                return 0;
            } catch (Throwable t) {
                System.out.println("[DesktopLauncher] OpenGL/LWJGL3 display initialization unavailable ("
                        + t.getClass().getSimpleName() + "); falling back to headless simulation mode.");
            }
        }

        return runHeadlessReleaseCycle(seed, ticks, backendUrl);
    }

    public static boolean isDisplayAvailable() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            String display = System.getenv("DISPLAY");
            String wayland = System.getenv("WAYLAND_DISPLAY");
            return (display != null && !display.isBlank()) || (wayland != null && !wayland.isBlank());
        }
        return true;
    }

    public static int runHeadlessReleaseCycle(long seed, int ticks, String backendUrl) {
        ConvoyConfig config = ConvoyConfig.loadDefault();
        ChronoConvoyGame game = new ChronoConvoyGame(config, seed, backendUrl);
        try {
            BackendApiClient.RunVerifyResult verifyResult = game.executeFullSectorAndOasisCycle(ticks);
            ChronoConvoySimulationSession session = game.getSimulationSession();
            Convoy3DRenderer renderer = game.getRenderer3D();

            System.out.println("================================================================================");
            System.out.println("  CHRONO-CONVOY: IRON DRIFT v1.0.0 — FULL RELEASE INTEGRATION SUMMARY");
            System.out.println("================================================================================");
            System.out.printf(Locale.US,
                    "  Simulation Ticks     : %d (Seed: %d, Schema: %s)%n",
                    session.getPhysicsSystem().getCompletedTicks(), seed, config.getSchemaId());
            System.out.printf(Locale.US,
                    "  Locomotive Position  : X=%.2f m, Y=%.2f m | Speed=%.2f m/s | Mass=%.2f t%n",
                    session.getPhysicsSystem().getLocomotiveBody().getPosition().x,
                    session.getPhysicsSystem().getLocomotiveBody().getPosition().y,
                    session.getPhysicsSystem().getForwardSpeedMps(),
                    session.getPhysicsSystem().getTotalConvoyMassTons());
            System.out.printf(Locale.US,
                    "  Reactor Allocation   : Shields=%.0fW | Artillery=%.0fW | Thrusters=%.0fW | Heat=%.1f%%%n",
                    session.getReactorController().getShieldsWatts(),
                    session.getReactorController().getArtilleryWatts(),
                    session.getReactorController().getThrustersWatts(),
                    session.getReactorController().getCurrentHeat());
            System.out.printf(Locale.US,
                    "  3D Viewport & Meshes : %d OBJ Models Loaded (%d total tris) | %d Frames Synced%n",
                    renderer.getAllMeshMetadata().size(),
                    renderer.getTotalVehicleTriangles(),
                    renderer.getRenderedFrameCount());
            System.out.printf(Locale.US,
                    "  Adaptive Audio Mixer : %d Stems (Chug Vol=%.2f, Pitch=%.2f) | %d SFX Cues (%d Triggered)%n",
                    game.getAudioMixer().getStems().size(),
                    game.getAudioMixer().getStemVolume(com.chronoconvoy.client.audio.AdaptiveAudioMixer.STEM_LOCOMOTIVE_CHUG),
                    game.getAudioMixer().getStem(com.chronoconvoy.client.audio.AdaptiveAudioMixer.STEM_LOCOMOTIVE_CHUG).getCurrentPitch(),
                    game.getAudioMixer().getSfxCues().size(),
                    game.getAudioMixer().getTotalSfxTriggers());
            System.out.printf(Locale.US,
                    "  Oasis Hub & Ledger   : Hub=%s | Scrip Balance=%d | Live Backend=%s%n",
                    game.getOasisHubScreen().getHubId(),
                    verifyResult.getScripBalanceAfter(),
                    verifyResult.isLiveBackendUsed());
            System.out.printf(Locale.US,
                    "  Replay Verification  : Verified=%s | RunID=%s | StateHash=%s%n",
                    verifyResult.isVerified(),
                    verifyResult.getRunId(),
                    verifyResult.getFinalStateHash());
            System.out.println("================================================================================");
            return verifyResult.isVerified() ? 0 : 1;
        } finally {
            game.dispose();
        }
    }

    private static int parsePositiveInt(String val, int fallback) {
        try {
            int parsed = Integer.parseInt(val.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String val, long fallback) {
        try {
            return Long.parseLong(val.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
