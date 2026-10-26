package com.chronoconvoy.qa;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Initializes the LibGDX Headless backend and Box2D JNI natives once per JVM
 * so deterministic physics and economy tests can run without an OpenGL context.
 */
public final class HeadlessGdxBootstrap {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static HeadlessApplication application;

    public record ConvoyConfig(
            float fixedTimeStep,
            int velocityIterations,
            int positionIterations,
            int maxReactorWatts,
            int overclockMaxReactorWatts,
            int carCount,
            float maxLocomotiveSpeed,
            int oasisSpreadBps,
            int oasisTaxBps,
            long coolingRodSinkCredits
    ) {
        public static ConvoyConfig defaults() {
            return new ConvoyConfig(
                    1.0f / 60.0f,
                    8,
                    3,
                    100,
                    130,
                    3,
                    38.0f,
                    500,
                    400,
                    95L
            );
        }
    }

    private HeadlessGdxBootstrap() {
    }

    public static synchronized void init() {
        if (INITIALIZED.get()) {
            return;
        }
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 60;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
        Box2D.init();
        INITIALIZED.set(true);
    }

    public static ConvoyConfig loadConfig() {
        init();
        File designConfig = new File("/workspace/chrono-convoy-iron-drift/docs/design/convoy_config.json");
        if (!designConfig.exists() || !designConfig.isFile()) {
            return ConvoyConfig.defaults();
        }
        try (InputStream in = new FileInputStream(designConfig)) {
            JsonValue root = new JsonReader().parse(in);
            ConvoyConfig def = ConvoyConfig.defaults();
            float dt = root.getFloat("physics_step_sec", def.fixedTimeStep());
            JsonValue reactor = root.get("reactor");
            int maxWatts = reactor != null ? reactor.getInt("total_watts_pool", def.maxReactorWatts()) : def.maxReactorWatts();
            int overclockWatts = reactor != null ? reactor.getInt("overclock_max_watts_pool", def.overclockMaxReactorWatts()) : def.overclockMaxReactorWatts();
            JsonValue loco = root.get("locomotive");
            float maxSpeed = loco != null ? loco.getFloat("overclock_max_speed_mps", def.maxLocomotiveSpeed()) : def.maxLocomotiveSpeed();
            JsonValue econ = root.get("economy");
            int spreadBps = econ != null ? Math.round(econ.getFloat("market_transaction_fee_pct", 0.05f) * 10_000f) : def.oasisSpreadBps();
            int taxBps = econ != null ? Math.round(econ.getFloat("oasis_infrastructure_tax_pct", 0.045f) * 10_000f) : def.oasisTaxBps();
            return new ConvoyConfig(dt, 8, 3, maxWatts, overclockWatts, 3, maxSpeed, spreadBps, taxBps, 95L);
        } catch (Exception ignored) {
            return ConvoyConfig.defaults();
        }
    }

    public static boolean isInitialized() {
        return INITIALIZED.get() && Gdx.files != null;
    }
}
