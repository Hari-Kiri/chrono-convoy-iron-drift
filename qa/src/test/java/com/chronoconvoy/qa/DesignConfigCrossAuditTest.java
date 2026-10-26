package com.chronoconvoy.qa;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.chronoconvoy.qa.ReactorPowerController.Subsystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QA-01D: Cross-Discipline Schema & Parametric GDD (convoy_config.json) Audit")
class DesignConfigCrossAuditTest {

    @BeforeAll
    static void setup() {
        HeadlessGdxBootstrap.init();
    }

    @Test
    @DisplayName("1. Parametric GDD convoy_config.json loads with valid 60Hz tick rate, 100W/130W reactor pool, and Box2D coupler limits")
    void testConvoyConfigSchemaInvariants() throws Exception {
        HeadlessGdxBootstrap.ConvoyConfig cfg = HeadlessGdxBootstrap.loadConfig();
        assertEquals(1.0f / 60.0f, cfg.fixedTimeStep(), 1e-5f);
        assertEquals(100, cfg.maxReactorWatts());
        assertEquals(130, cfg.overclockMaxReactorWatts());
        assertEquals(500, cfg.oasisSpreadBps());
        assertTrue(cfg.oasisTaxBps() >= 200 && cfg.oasisTaxBps() <= 1000, "Infrastructure tax bps must be in [200, 1000]");

        File file = new File("/workspace/chrono-convoy-iron-drift/docs/design/convoy_config.json");
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                JsonValue root = new JsonReader().parse(in);
                assertEquals(60, root.getInt("tick_rate_hz"));
                JsonValue reactor = root.get("reactor");
                JsonValue defaults = reactor.get("default_allocation_watts");
                int defaultSum = defaults.getInt("shields") + defaults.getInt("artillery") + defaults.getInt("thrusters");
                assertTrue(defaultSum <= reactor.getInt("total_watts_pool"),
                        "Default subsystem watts sum (" + defaultSum + ") must be <= total_watts_pool");
            }
        }
    }

    @Test
    @DisplayName("2. 100W Canonical Reactor Pool & 130W Overclock Pool enforce strict subsystem conservation")
    void test100WattAnd130WattCanonicalReactorPools() {
        ReactorPowerController baseReactor = new ReactorPowerController(100L);
        baseReactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 30L);
        baseReactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 35L);
        baseReactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 35L);
        assertEquals(100L, baseReactor.getTotalAllocatedWatts());
        assertThrows(IllegalStateException.class,
                () -> baseReactor.allocateSubsystemWatts(Subsystem.RADAR_ECM, 1L));

        ReactorPowerController overclockReactor = new ReactorPowerController(130L);
        overclockReactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 40L);
        overclockReactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 45L);
        overclockReactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 45L);
        assertEquals(130L, overclockReactor.getTotalAllocatedWatts());
        assertTrue(overclockReactor.verifyConservationInvariant());
    }
}
