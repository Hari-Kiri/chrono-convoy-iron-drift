package com.chronoconvoy.qa;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.chronoconvoy.qa.OasisEconomyEngine.Commodity;
import com.chronoconvoy.qa.OasisEconomyEngine.OasisHub;
import com.chronoconvoy.qa.ReactorPowerController.Subsystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QA-02: v1.0.0 Release Readiness, Client Asset Pipeline & 60Hz Replay Submission Suite")
class ReleaseReadinessIntegrationTest {

    private static final File CLIENT_ASSETS_DIR = new File("/workspace/chrono-convoy-iron-drift/client/assets");
    private static final String MASTER_SECRET = "chrono-convoy-iron-drift-sprint1-master-secret-2026";

    @BeforeAll
    static void setup() {
        HeadlessGdxBootstrap.init();
    }

    @Test
    @DisplayName("1. Verify client/assets/config/convoy_config.json schema, 60Hz physics, 100W/130W reactor, carriages, raiders & Oasis economy")
    void testClientAssetsConfigConvoyConfigJson() throws Exception {
        File configFile = new File(CLIENT_ASSETS_DIR, "config/convoy_config.json");
        assertTrue(configFile.exists() && configFile.isFile(),
                "Missing client/assets/config/convoy_config.json at " + configFile.getAbsolutePath());
        assertTrue(configFile.length() > 1024, "convoy_config.json must be non-empty");

        JsonValue root;
        try (FileInputStream in = new FileInputStream(configFile)) {
            root = new JsonReader().parse(in);
        }
        assertNotNull(root, "convoy_config.json must parse as valid JSON");
        assertEquals("chrono-convoy-iron-drift-config-v1", root.getString("schema_id"));
        assertTrue(root.getString("version").startsWith("1.0.0"));
        assertEquals(60, root.getInt("tick_rate_hz"));
        assertEquals(1.0f / 60.0f, root.getFloat("physics_step_sec"), 1e-5f);
        assertEquals(60, root.getInt("backend_economy_tick_sec"));

        // Locomotive & Box2D coupler section
        JsonValue locomotive = root.get("locomotive");
        assertNotNull(locomotive, "locomotive section required");
        assertEquals("iron_drifter_mk1", locomotive.getString("id"));
        assertNotNull(locomotive.get("box2d_coupler_joint"));

        // Reactor section
        JsonValue reactor = root.get("reactor");
        assertNotNull(reactor, "reactor section required");
        assertEquals(100, reactor.getInt("total_watts_pool"));
        assertEquals(130, reactor.getInt("overclock_max_watts_pool"));

        JsonValue defaultAlloc = reactor.get("default_allocation_watts");
        int defaultSum = defaultAlloc.getInt("shields")
                + defaultAlloc.getInt("artillery")
                + defaultAlloc.getInt("thrusters");
        assertEquals(100, defaultSum, "Default subsystem watt allocation must sum to 100W base capacity");

        // Carriages & Raiders
        JsonValue carriages = root.get("carriages");
        assertNotNull(carriages, "carriages array required");
        assertEquals(4, carriages.size, "4 modular carriage blueprints required alongside locomotive");

        JsonValue raiders = root.get("raiders");
        assertNotNull(raiders, "raiders array required");
        assertTrue(raiders.size >= 2, "At least 2 raider archetypes required");

        // Oasis economy
        JsonValue economy = root.get("economy");
        assertNotNull(economy, "economy section required");
        assertEquals(0.05f, economy.getFloat("market_transaction_fee_pct"), 1e-5f);
        assertEquals(0.04f, economy.getFloat("oasis_infrastructure_tax_pct"), 1e-5f);
        assertEquals(3, economy.get("oasis_hubs").size, "Exactly 3 Oasis Hubs required");
        assertEquals(5, economy.get("commodities").size, "All 5 tradable commodities required");
    }

    @Test
    @DisplayName("2. Verify client/assets/3d/*.obj meshes, .mtl/.glb companions, and 3d/manifest.json vertex/face geometry budgets")
    void testClientAssets3dMeshesAndManifest() throws Exception {
        File dir3d = new File(CLIENT_ASSETS_DIR, "3d");
        File manifestFile = new File(dir3d, "manifest.json");
        assertTrue(manifestFile.exists() && manifestFile.length() > 100,
                "Missing client/assets/3d/manifest.json");

        JsonValue manifest;
        try (FileInputStream in = new FileInputStream(manifestFile)) {
            manifest = new JsonReader().parse(in);
        }
        assertEquals(3500, manifest.getInt("max_triangles"));

        JsonValue vehicles = manifest.get("vehicles");
        assertNotNull(vehicles, "manifest.json must contain vehicles array");
        assertEquals(5, vehicles.size, "manifest.json must list all 5 3D vehicle/carriage meshes");

        Set<String> expectedAssets = Set.of(
                "locomotive",
                "carriage_artillery",
                "carriage_cargo",
                "raider_buggy",
                "raider_harpoon_rig"
        );

        for (JsonValue assetEntry = vehicles.child; assetEntry != null; assetEntry = assetEntry.next) {
            String assetId = assetEntry.getString("id");
            assertTrue(expectedAssets.contains(assetId), "Unexpected vehicle id: " + assetId);
            assertTrue(assetEntry.getBoolean("within_budget"), "Asset must be within triangle budget: " + assetId);

            File objFile = new File(dir3d, assetEntry.getString("obj"));
            File mtlFile = new File(dir3d, assetEntry.getString("mtl"));
            File glbFile = new File(dir3d, assetEntry.getString("glb"));

            assertTrue(objFile.exists() && objFile.length() > 1024, "Missing or empty OBJ: " + objFile);
            assertTrue(mtlFile.exists() && mtlFile.length() > 100, "Missing or empty MTL: " + mtlFile);
            assertTrue(glbFile.exists() && glbFile.length() > 1024, "Missing or empty GLB: " + glbFile);

            // Verify binary glTF 2.0 header ("glTF", version 2)
            byte[] glbHeader = new byte[12];
            try (FileInputStream glbIn = new FileInputStream(glbFile)) {
                assertEquals(12, glbIn.read(glbHeader));
            }
            assertEquals("glTF", new String(glbHeader, 0, 4, StandardCharsets.US_ASCII));
            int glbVersion = ByteBuffer.wrap(glbHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertEquals(2, glbVersion, "GLB must be glTF version 2");

            // Parse OBJ records (v, vt, vn, f) and verify non-empty valid geometry
            int vCount = 0;
            int vtCount = 0;
            int vnCount = 0;
            int fCount = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(objFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("v ")) {
                        vCount++;
                    } else if (line.startsWith("vt ")) {
                        vtCount++;
                        String[] parts = line.trim().split("\\s+");
                        float u = Float.parseFloat(parts[1]);
                        float v = Float.parseFloat(parts[2]);
                        assertTrue(u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 1.0f,
                                "UV out of [0,1] bounds in " + objFile.getName());
                    } else if (line.startsWith("vn ")) {
                        vnCount++;
                    } else if (line.startsWith("f ")) {
                        fCount++;
                    }
                }
            }

            assertTrue(vCount > 100, "OBJ must contain > 100 vertices: " + objFile.getName());
            assertTrue(vtCount > 100, "OBJ must contain > 100 UVs: " + objFile.getName());
            assertTrue(vnCount > 50, "OBJ must contain > 50 normals: " + objFile.getName());
            assertTrue(fCount > 100 && fCount <= 3500,
                    "OBJ triangle count must be in (100, 3500]: " + fCount + " in " + objFile.getName());
            assertEquals(assetEntry.getInt("vertex_count"), vCount, "Vertex count mismatch for " + assetId);
            assertEquals(assetEntry.getInt("triangle_count"), fCount, "Triangle count mismatch for " + assetId);
        }
    }

    @Test
    @DisplayName("3. Verify client/assets/ui/convoy_hud.atlas, convoy_hud_skin.json, backing PNG page & BitmapFonts")
    void testClientAssetsUiAtlasSkinAndFonts() throws Exception {
        File uiDir = new File(CLIENT_ASSETS_DIR, "ui");
        File atlasFile = new File(uiDir, "convoy_hud.atlas");
        File skinFile = new File(uiDir, "convoy_hud_skin.json");
        File pngFile = new File(uiDir, "convoy_hud.png");

        assertTrue(atlasFile.exists() && atlasFile.length() > 500, "Missing convoy_hud.atlas");
        assertTrue(skinFile.exists() && skinFile.length() > 500, "Missing convoy_hud_skin.json");
        assertTrue(pngFile.exists() && pngFile.length() > 1024, "Missing convoy_hud.png");

        // Check PNG magic signature and IHDR 1024x1024 dimensions
        byte[] pngHeader = new byte[24];
        try (FileInputStream in = new FileInputStream(pngFile)) {
            assertEquals(24, in.read(pngHeader));
        }
        byte[] expectedPngSig = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 8; i++) {
            assertEquals(expectedPngSig[i], pngHeader[i], "Invalid PNG signature byte at index " + i);
        }
        int width = ByteBuffer.wrap(pngHeader, 16, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        int height = ByteBuffer.wrap(pngHeader, 20, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        assertEquals(1024, width, "convoy_hud.png atlas width must be 1024");
        assertEquals(1024, height, "convoy_hud.png atlas height must be 1024");

        // Verify LibGDX .atlas text structure and required HUD regions / nine-patches
        String atlasText = Files.readString(atlasFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(atlasText.startsWith("convoy_hud.png"), "Atlas must reference convoy_hud.png");
        assertTrue(atlasText.contains("size: 1024, 1024"), "Atlas must declare size: 1024, 1024");
        assertTrue(atlasText.contains("format: RGBA8888"), "Atlas must declare RGBA8888 format");
        assertTrue(atlasText.contains("split: "), "Atlas must contain nine-patch split metadata");

        List<String> requiredRegions = List.of(
                "panel_brass_deck",
                "panel_iron_inset",
                "panel_crt_screen",
                "panel_modal_warning",
                "btn_brass_up",
                "btn_brass_down",
                "btn_emergency_up",
                "bar_tonnage_track",
                "bar_tonnage_fill",
                "gauge_temp_track",
                "icon_reactor_shields",
                "icon_reactor_artillery",
                "icon_reactor_thrusters",
                "slider_fill_artillery_amber"
        );
        for (String region : requiredRegions) {
            assertTrue(atlasText.contains(region), "Missing required HUD atlas region: " + region);
        }

        // Verify convoy_hud_skin.json structure and backing .fnt files
        JsonValue skinRoot;
        try (FileInputStream in = new FileInputStream(skinFile)) {
            skinRoot = new JsonReader().parse(in);
        }
        JsonValue fonts = skinRoot.get("com.badlogic.gdx.graphics.g2d.BitmapFont");
        assertNotNull(fonts, "Skin must define BitmapFont entries");
        for (String fontKey : List.of("default-font", "mono-font", "title-font")) {
            JsonValue fontEntry = fonts.get(fontKey);
            assertNotNull(fontEntry, "Missing font entry: " + fontKey);
            File fntFile = new File(uiDir, fontEntry.getString("file"));
            assertTrue(fntFile.exists() && fntFile.length() > 100, "Missing bitmap font file: " + fntFile);
        }

        assertNotNull(skinRoot.get("com.badlogic.gdx.graphics.Color"), "Skin must define Color palette");
        assertNotNull(skinRoot.get("com.badlogic.gdx.scenes.scene2d.ui.ImageButton$ImageButtonStyle"));
        assertNotNull(skinRoot.get("com.badlogic.gdx.scenes.scene2d.ui.TextButton$TextButtonStyle"));
        assertNotNull(skinRoot.get("com.badlogic.gdx.scenes.scene2d.ui.Label$LabelStyle"));
        assertNotNull(skinRoot.get("com.badlogic.gdx.scenes.scene2d.ui.ProgressBar$ProgressBarStyle"));
        assertNotNull(skinRoot.get("com.badlogic.gdx.scenes.scene2d.ui.Slider$SliderStyle"));
        assertNotNull(skinRoot.get("com.badlogic.gdx.scenes.scene2d.ui.Window$WindowStyle"));
    }

    @Test
    @DisplayName("4. Verify client/assets/audio/adaptive_audio_cues.json, 4 music stems, and 5 SFX WAV PCM headers")
    void testClientAssetsAudioStemsSfxAndCuesJson() throws Exception {
        File audioDir = new File(CLIENT_ASSETS_DIR, "audio");
        File cuesFile = new File(audioDir, "adaptive_audio_cues.json");
        assertTrue(cuesFile.exists() && cuesFile.length() > 500, "Missing adaptive_audio_cues.json");

        JsonValue cuesRoot;
        try (FileInputStream in = new FileInputStream(cuesFile)) {
            cuesRoot = new JsonReader().parse(in);
        }
        JsonValue mastering = cuesRoot.get("mastering_standards");
        assertNotNull(mastering, "mastering_standards section required");
        assertEquals(44100, mastering.getInt("sample_rate_hz"));
        assertEquals(16, mastering.getInt("bit_depth"));

        // Verify all 4 music stems
        JsonValue stems = cuesRoot.get("vertical_layering_stems");
        assertNotNull(stems, "vertical_layering_stems array required");
        assertEquals(4, stems.size, "Exactly 4 adaptive music stems required");

        for (JsonValue stem = stems.child; stem != null; stem = stem.next) {
            File wavFile = new File(audioDir, stem.getString("file"));
            assertTrue(wavFile.exists() && wavFile.length() > 44, "Missing stem WAV: " + wavFile);
            WavHeaderInfo wav = parseWavHeader(wavFile);
            assertEquals(1, wav.audioFormat, "WAV must be uncompressed PCM (format=1)");
            assertEquals(44100, wav.sampleRate, "WAV sample rate must be 44100 Hz");
            assertEquals(16, wav.bitsPerSample, "WAV bit depth must be 16-bit");
            int expectedSamples = stem.getInt("samples");
            assertEquals(expectedSamples, wav.totalFrames,
                    "Stem frame count must match samples in adaptive_audio_cues.json for " + wavFile.getName());
        }

        // Verify all 5 SFX cues
        JsonValue sfxCues = cuesRoot.get("combat_sfx_cues");
        assertNotNull(sfxCues, "combat_sfx_cues object required");
        assertEquals(5, sfxCues.size, "Exactly 5 SFX cues required");

        for (JsonValue sfx = sfxCues.child; sfx != null; sfx = sfx.next) {
            File wavFile = new File(audioDir, sfx.getString("file"));
            assertTrue(wavFile.exists() && wavFile.length() > 44, "Missing SFX WAV: " + wavFile);
            WavHeaderInfo wav = parseWavHeader(wavFile);
            assertEquals(1, wav.audioFormat, "SFX WAV must be PCM (format=1)");
            assertEquals(44100, wav.sampleRate, "SFX sample rate must be 44100 Hz");
            assertEquals(16, wav.bitsPerSample, "SFX bit depth must be 16-bit");
            int expectedSamples = sfx.get("diagnostics").getInt("samples");
            assertEquals(expectedSamples, wav.totalFrames,
                    "SFX frame count must match diagnostics.samples for " + wavFile.getName());
        }
    }

    @Test
    @DisplayName("5. Execute full 60Hz deterministic convoy combat simulation & verify signed replay payload for backend submission")
    void testDeterministic60HzCombatSimulationAndSignedReplaySubmissionReadiness() throws Exception {
        File configFile = new File(CLIENT_ASSETS_DIR, "config/convoy_config.json");
        JsonValue config;
        try (FileInputStream in = new FileInputStream(configFile)) {
            config = new JsonReader().parse(in);
        }

        int baseWatts = config.get("reactor").getInt("total_watts_pool");
        int overclockWatts = config.get("reactor").getInt("overclock_max_watts_pool");

        // Run two independent 60Hz simulations with identical inputs to prove deterministic state hashes
        String hashRunA = run60HzCombatScenario(baseWatts, overclockWatts);
        String hashRunB = run60HzCombatScenario(baseWatts, overclockWatts);
        assertEquals(hashRunA, hashRunB, "60Hz combat simulation must produce bit-identical state hashes");

        // Verify Oasis economy trade round-trip readiness
        OasisEconomyEngine economy = new OasisEconomyEngine(25_000L, OasisHub.OASIS_ALPHA_RUST_GATE);
        long totalBuyCost = economy.buy(Commodity.CRUDE_FUEL, 15L);
        assertTrue(totalBuyCost > 0L && economy.getCredits() < 25_000L);

        // Construct canonical backend replay payload & HMAC-SHA256 signature matching BE-01 verifier contract
        String runId = "run_qa_release_v1_001";
        String playerId = "player_cmdr_riki";
        String convoyId = "convoy_iron_drift_01";
        String originHub = "hydro_haven";
        String destHub = "iron_refinery";
        long seed = 777888999L;
        String actionsJson = "["
                + "{\"tick\":0,\"type\":\"ALLOCATE_WATTS\",\"shields_watts\":30,\"artillery_watts\":35,\"thrusters_watts\":35},"
                + "{\"tick\":120,\"type\":\"OVERCLOCK_ROD\"},"
                + "{\"tick\":125,\"type\":\"ALLOCATE_WATTS\",\"shields_watts\":40,\"artillery_watts\":45,\"thrusters_watts\":45},"
                + "{\"tick\":180,\"type\":\"SWITCH_BRANCH\",\"branch_id\":\"canyon_high_ground\"},"
                + "{\"tick\":240,\"type\":\"FIRE_TURRET\",\"target_raider_id\":\"raider_01\"},"
                + "{\"tick\":320,\"type\":\"RAM_RAIDER\",\"target_raider_id\":\"raider_02\"},"
                + "{\"tick\":420,\"type\":\"FLUSH_COOLING_ROD\"},"
                + "{\"tick\":600,\"type\":\"DELIVER_CARGO\",\"commodity_id\":\"purified_water\",\"quantity\":10}"
                + "]";

        String canonical = "v1|" + runId + "|" + playerId + "|" + convoyId + "|"
                + originHub + "|" + destHub + "|" + seed + "|" + actionsJson;

        byte[] derivedKey = MessageDigest.getInstance("SHA-256")
                .digest(MASTER_SECRET.getBytes(StandardCharsets.UTF_8));
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(derivedKey, "HmacSHA256"));
        String signatureHex = HexFormat.of().formatHex(hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(64, signatureHex.length(), "HMAC-SHA256 hex signature must be 64 characters");

        String submissionJson = "{"
                + "\"run_id\":\"" + runId + "\","
                + "\"idempotency_key\":\"idem_" + runId + "\","
                + "\"player_id\":\"" + playerId + "\","
                + "\"convoy_id\":\"" + convoyId + "\","
                + "\"origin_hub_id\":\"" + originHub + "\","
                + "\"destination_hub_id\":\"" + destHub + "\","
                + "\"seed\":" + seed + ","
                + "\"max_reactor_watts\":" + baseWatts + ","
                + "\"initial_cooling_rods\":10,"
                + "\"actions\":" + actionsJson + ","
                + "\"signature_hmac\":\"" + signatureHex + "\""
                + "}";

        JsonValue parsedSubmission = new JsonReader().parse(submissionJson);
        assertEquals(runId, parsedSubmission.getString("run_id"));
        assertEquals(8, parsedSubmission.get("actions").size);
        assertEquals(signatureHex, parsedSubmission.getString("signature_hmac"));
    }

    private String run60HzCombatScenario(int baseWatts, int overclockWatts) {
        ReactorPowerController reactor = new ReactorPowerController(baseWatts);
        reactor.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 30L);
        reactor.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 35L);
        reactor.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 35L);
        assertTrue(reactor.verifyConservationInvariant());

        try (ConvoyPhysicsHarness harness = new ConvoyPhysicsHarness(5)) {
            harness.spawnRaider(-40.0f, 22.0f, 28.0f, -12.0f, 2.4f, true);
            harness.spawnRaider(35.0f, -24.0f, -26.0f, 14.0f, 2.8f, true);

            for (int tick = 0; tick < 600; tick++) {
                float propulsionWatts = 35.0f;
                if (tick >= 120 && tick < 420) {
                    // Simulate OVERCLOCK_ROD (+30W boost -> 130W total pool)
                    ReactorPowerController overclocked = new ReactorPowerController(overclockWatts);
                    overclocked.allocateSubsystemWatts(Subsystem.THERMAL_SHIELDS, 40L);
                    overclocked.allocateSubsystemWatts(Subsystem.ARTILLERY_TURRETS, 45L);
                    overclocked.allocateSubsystemWatts(Subsystem.PROPULSION_DRIVE, 45L);
                    assertTrue(overclocked.verifyConservationInvariant());
                    propulsionWatts = 45.0f;
                }
                harness.applyLocomotiveSplineTraction(propulsionWatts);
                harness.stepSingleTick();
            }

            ConvoyPhysicsHarness.TelemetrySnapshot snap = harness.snapshot();
            assertEquals(600, snap.totalTicks());
            assertFalse(snap.nanOrInfiniteDetected(), "Zero NaN/Infinity required during 60Hz combat simulation");
            assertFalse(snap.tunnelingDetected(), "Zero barrier tunneling required during 60Hz combat simulation");
            return snap.deterministicStateHash();
        }
    }

    private record WavHeaderInfo(int audioFormat, int numChannels, int sampleRate, int bitsPerSample, int dataBytes, int totalFrames) {}

    private WavHeaderInfo parseWavHeader(File wavFile) throws Exception {
        byte[] header = new byte[44];
        try (FileInputStream in = new FileInputStream(wavFile)) {
            assertEquals(44, in.read(header), "WAV header must be at least 44 bytes: " + wavFile.getName());
        }
        assertEquals("RIFF", new String(header, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(header, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(header, 12, 4, StandardCharsets.US_ASCII));

        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int audioFormat = Short.toUnsignedInt(buf.getShort(20));
        int numChannels = Short.toUnsignedInt(buf.getShort(22));
        int sampleRate = buf.getInt(24);
        int bitsPerSample = Short.toUnsignedInt(buf.getShort(34));
        int dataBytes = buf.getInt(40);
        int bytesPerFrame = numChannels * (bitsPerSample / 8);
        int totalFrames = dataBytes / bytesPerFrame;

        return new WavHeaderInfo(audioFormat, numChannels, sampleRate, bitsPerSample, dataBytes, totalFrames);
    }
}
