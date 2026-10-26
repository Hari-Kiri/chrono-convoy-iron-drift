package com.chronoconvoy.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.chronoconvoy.client.combat.TurretCombatSystem;
import com.chronoconvoy.client.config.AssetSupport;
import com.chronoconvoy.client.physics.ConvoyPhysicsSystem;
import com.chronoconvoy.client.track.SplineTrackSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3D Viewport & Mesh Renderer for Chrono-Convoy: Iron Drift (Sprint 2).
 *
 * Loads @Onigiri's 5 low-poly desert vehicle meshes from assets/3d/:
 * - locomotive.obj
 * - carriage_artillery.obj
 * - carriage_cargo.obj
 * - raider_buggy.obj
 * - raider_harpoon_rig.obj
 *
 * Uses LibGDX ModelBatch, Environment (desert directional sunlight + ambient), PerspectiveCamera,
 * and ObjLoader with a procedural ModelBuilder & headless-safe scene-graph fallback so transforms,
 * wheel rotation, turret yaw, and camera tracking work identically in both OpenGL and headless modes.
 */
public class Convoy3DRenderer implements Disposable {

    public static final String MESH_LOCOMOTIVE = "locomotive.obj";
    public static final String MESH_CARRIAGE_ARTILLERY = "carriage_artillery.obj";
    public static final String MESH_CARRIAGE_CARGO = "carriage_cargo.obj";
    public static final String MESH_RAIDER_BUGGY = "raider_buggy.obj";
    public static final String MESH_RAIDER_HARPOON_RIG = "raider_harpoon_rig.obj";

    public static final List<String> REQUIRED_VEHICLE_MESHES = List.of(
        MESH_LOCOMOTIVE,
        MESH_CARRIAGE_ARTILLERY,
        MESH_CARRIAGE_CARGO,
        MESH_RAIDER_BUGGY,
        MESH_RAIDER_HARPOON_RIG
    );

    public static final class ObjMeshMetadata {
        private final String fileName;
        private final int vertexCount;
        private final int normalCount;
        private final int uvCount;
        private final int triangleCount;
        private final Set<String> objectNodeNames;
        private final Set<String> materialNames;
        private final BoundingBox boundingBox;

        public ObjMeshMetadata(
                String fileName,
                int vertexCount,
                int normalCount,
                int uvCount,
                int triangleCount,
                Set<String> objectNodeNames,
                Set<String> materialNames,
                BoundingBox boundingBox) {
            this.fileName = fileName;
            this.vertexCount = vertexCount;
            this.normalCount = normalCount;
            this.uvCount = uvCount;
            this.triangleCount = triangleCount;
            this.objectNodeNames = Collections.unmodifiableSet(new LinkedHashSet<>(objectNodeNames));
            this.materialNames = Collections.unmodifiableSet(new LinkedHashSet<>(materialNames));
            this.boundingBox = new BoundingBox(boundingBox);
        }

        public String getFileName() { return fileName; }
        public int getVertexCount() { return vertexCount; }
        public int getNormalCount() { return normalCount; }
        public int getUvCount() { return uvCount; }
        public int getTriangleCount() { return triangleCount; }
        public Set<String> getObjectNodeNames() { return objectNodeNames; }
        public Set<String> getMaterialNames() { return materialNames; }
        public BoundingBox getBoundingBox() { return boundingBox; }
    }

    private final PerspectiveCamera camera;
    private final Environment environment;
    private final DirectionalLight desertSunLight;
    private ModelBatch modelBatch;

    private final Map<String, ObjMeshMetadata> meshMetadataByFile = new LinkedHashMap<>();
    private final Map<String, Model> loadedModels = new LinkedHashMap<>();
    private Model trackRailModel;

    private ModelInstance locomotiveInstance;
    private final List<ModelInstance> carriageInstances = new ArrayList<>();
    private final List<ModelInstance> raiderInstances = new ArrayList<>();
    private final List<ModelInstance> trackRailInstances = new ArrayList<>();

    private float cumulativeWheelRotationDeg = 0.0f;
    private float currentTurretYawDeg = 0.0f;
    private int renderedFrameCount = 0;
    private int lastDrawCallModelCount = 0;
    private boolean disposed = false;

    public Convoy3DRenderer() {
        this(1280, 720);
    }

    public Convoy3DRenderer(int viewportWidth, int viewportHeight) {
        AssetSupport.ensureHeadlessFilesInitialized();

        this.camera = new PerspectiveCamera(62.0f, Math.max(1, viewportWidth), Math.max(1, viewportHeight));
        this.camera.position.set(-18.0f, 22.0f, -24.0f);
        this.camera.lookAt(12.0f, 1.5f, 0.0f);
        this.camera.near = 0.5f;
        this.camera.far = 450.0f;
        this.camera.update();

        this.environment = new Environment();
        this.environment.set(new ColorAttribute(
                ColorAttribute.AmbientLight, 0.52f, 0.46f, 0.38f, 1.0f));
        this.desertSunLight = new DirectionalLight().set(
                0.98f, 0.86f, 0.68f, -0.45f, -0.82f, 0.35f);
        this.environment.add(this.desertSunLight);

        if (isOpenGlAvailable()) {
            try {
                this.modelBatch = new ModelBatch();
            } catch (Exception ignored) {
                this.modelBatch = null;
            }
        }

        loadAllVehicleMeshes();
        initializeDefaultConvoyInstances();
    }

    public final boolean isOpenGlAvailable() {
        return Gdx.gl != null && Gdx.gl20 != null;
    }

    private void loadAllVehicleMeshes() {
        ObjLoader objLoader = isOpenGlAvailable() ? new ObjLoader() : null;
        ModelBuilder modelBuilder = isOpenGlAvailable() ? new ModelBuilder() : null;

        for (String meshFile : REQUIRED_VEHICLE_MESHES) {
            ObjMeshMetadata metadata = parseObjMetadata("3d/" + meshFile);
            meshMetadataByFile.put(meshFile, metadata);

            Model model = null;
            if (isOpenGlAvailable() && objLoader != null) {
                try {
                    model = objLoader.loadModel(AssetSupport.resolveFileHandle("3d/" + meshFile));
                } catch (Exception e) {
                    model = buildProceduralGlModel(modelBuilder, metadata);
                }
            }
            if (model == null) {
                model = buildSceneGraphModel(metadata);
            } else {
                ensureExpectedNodesPresent(model, metadata);
            }
            loadedModels.put(meshFile, model);
        }

        if (isOpenGlAvailable() && modelBuilder != null) {
            try {
                Material railMat = new Material(ColorAttribute.createDiffuse(new Color(0.45f, 0.38f, 0.32f, 1.0f)));
                trackRailModel = modelBuilder.createBox(
                        12.0f, 0.25f, 3.2f,
                        railMat,
                        VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            } catch (Exception ignored) {
                trackRailModel = buildHeadlessRailModel();
            }
        } else {
            trackRailModel = buildHeadlessRailModel();
        }
    }

    private Model buildProceduralGlModel(ModelBuilder builder, ObjMeshMetadata meta) {
        if (builder == null) {
            return buildSceneGraphModel(meta);
        }
        try {
            Vector3 dim = new Vector3();
            meta.getBoundingBox().getDimensions(dim);
            Material mat = new Material(ColorAttribute.createDiffuse(new Color(0.76f, 0.52f, 0.28f, 1.0f)));
            Model m = builder.createBox(
                    Math.max(1.5f, dim.x),
                    Math.max(1.2f, dim.y),
                    Math.max(2.5f, dim.z),
                    mat,
                    VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            ensureExpectedNodesPresent(m, meta);
            return m;
        } catch (Exception e) {
            return buildSceneGraphModel(meta);
        }
    }

    private static Model buildSceneGraphModel(ObjMeshMetadata meta) {
        Model model = new Model();
        Material primaryMat = new Material("mat_rust_iron",
                ColorAttribute.createDiffuse(new Color(0.68f, 0.42f, 0.24f, 1.0f)));
        model.materials.add(primaryMat);
        ensureExpectedNodesPresent(model, meta);
        return model;
    }

    private static void ensureExpectedNodesPresent(Model model, ObjMeshMetadata meta) {
        Node chassis = model.getNode("chassis");
        if (chassis == null) {
            chassis = new Node();
            chassis.id = "chassis";
            model.nodes.add(chassis);
        }
        for (String nodeName : meta.getObjectNodeNames()) {
            if ("chassis".equals(nodeName)) {
                continue;
            }
            if (model.getNode(nodeName, true) == null) {
                Node child = new Node();
                child.id = nodeName;
                if ("turret_gun".equals(nodeName)) {
                    Node turretBase = model.getNode("turret_base", true);
                    if (turretBase == null) {
                        turretBase = new Node();
                        turretBase.id = "turret_base";
                        chassis.addChild(turretBase);
                    }
                    turretBase.addChild(child);
                } else {
                    chassis.addChild(child);
                }
            }
        }
    }

    private static Model buildHeadlessRailModel() {
        Model model = new Model();
        Node railNode = new Node();
        railNode.id = "rail_segment";
        model.nodes.add(railNode);
        return model;
    }

    private void initializeDefaultConvoyInstances() {
        Model locoModel = loadedModels.get(MESH_LOCOMOTIVE);
        this.locomotiveInstance = new ModelInstance(locoModel);

        carriageInstances.clear();
        carriageInstances.add(new ModelInstance(loadedModels.get(MESH_CARRIAGE_ARTILLERY)));
        carriageInstances.add(new ModelInstance(loadedModels.get(MESH_CARRIAGE_ARTILLERY)));
        carriageInstances.add(new ModelInstance(loadedModels.get(MESH_CARRIAGE_CARGO)));

        trackRailInstances.clear();
        for (int i = 0; i < 16; i++) {
            ModelInstance rail = new ModelInstance(trackRailModel);
            rail.transform.setToTranslation(i * 12.0f, -0.2f, 0.0f);
            trackRailInstances.add(rail);
        }
    }

    public static ObjMeshMetadata parseObjMetadata(String assetPath) {
        int vertices = 0;
        int normals = 0;
        int uvs = 0;
        int triangles = 0;
        Set<String> nodes = new LinkedHashSet<>();
        Set<String> materials = new LinkedHashSet<>();
        BoundingBox bbox = new BoundingBox();
        boolean hasVertex = false;

        try (InputStream in = AssetSupport.openStream(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("v ")) {
                    vertices++;
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 4) {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);
                        if (!hasVertex) {
                            bbox.set(new Vector3(x, y, z), new Vector3(x, y, z));
                            hasVertex = true;
                        } else {
                            bbox.ext(x, y, z);
                        }
                    }
                } else if (trimmed.startsWith("vn ")) {
                    normals++;
                } else if (trimmed.startsWith("vt ")) {
                    uvs++;
                } else if (trimmed.startsWith("f ")) {
                    String[] parts = trimmed.split("\\s+");
                    int faceVerts = parts.length - 1;
                    if (faceVerts >= 3) {
                        triangles += (faceVerts - 2);
                    }
                } else if (trimmed.startsWith("o ") || trimmed.startsWith("g ")) {
                    String name = trimmed.substring(2).trim();
                    if (!name.isEmpty()) {
                        nodes.add(name);
                    }
                } else if (trimmed.startsWith("usemtl ")) {
                    String mat = trimmed.substring(7).trim();
                    if (!mat.isEmpty()) {
                        materials.add(mat);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse 3D OBJ asset: " + assetPath, e);
        }

        String shortName = assetPath.contains("/")
                ? assetPath.substring(assetPath.lastIndexOf('/') + 1)
                : assetPath;
        return new ObjMeshMetadata(shortName, vertices, normals, uvs, triangles, nodes, materials, bbox);
    }

    public void syncWithSimulation(
            ConvoyPhysicsSystem physics,
            SplineTrackSystem trackSystem,
            TurretCombatSystem combatSystem,
            float deltaSeconds) {
        if (physics == null) {
            return;
        }

        // 1. Update wheel rotation angle from locomotive linear speed
        float speedMps = physics.getForwardSpeedMps();
        float wheelAngularSpeedDegPerSec = (speedMps / 0.85f) * (180.0f / (float) Math.PI);
        cumulativeWheelRotationDeg = (cumulativeWheelRotationDeg + wheelAngularSpeedDegPerSec * deltaSeconds) % 360.0f;

        // 2. Sync Locomotive 3D transform (Box2D X -> 3D X, Box2D Y lateral -> 3D Z, Y up)
        Vector2 locoPos = physics.getLocomotiveBody().getPosition();
        float locoYawDeg = (float) Math.toDegrees(physics.getLocomotiveBody().getAngle());
        setVehicleTransform(locomotiveInstance, locoPos.x, 0.0f, locoPos.y, locoYawDeg);
        rotateVehicleWheels(locomotiveInstance, cumulativeWheelRotationDeg);

        // 3. Sync articulated carriages
        List<ConvoyPhysicsSystem.CarriageUnit> cars = physics.getCarriages();
        while (carriageInstances.size() < cars.size()) {
            String role = cars.get(carriageInstances.size()).getRoleName();
            String meshKey = role.contains("CARGO")
                    ? MESH_CARRIAGE_CARGO
                    : MESH_CARRIAGE_ARTILLERY;
            carriageInstances.add(new ModelInstance(loadedModels.get(meshKey)));
        }

        if (combatSystem != null && combatSystem.getActiveRaiders().size > 0) {
            TurretCombatSystem.RaiderTarget nearest = combatSystem.getActiveRaiders().first();
            float dx = nearest.position.x - locoPos.x;
            float dz = nearest.position.y - locoPos.y;
            currentTurretYawDeg = (float) Math.toDegrees(Math.atan2(dz, dx));
        } else {
            currentTurretYawDeg = (currentTurretYawDeg + 25.0f * deltaSeconds) % 360.0f;
        }

        for (int i = 0; i < cars.size(); i++) {
            ConvoyPhysicsSystem.CarriageUnit car = cars.get(i);
            Vector2 pos = car.getBody().getPosition();
            float yawDeg = (float) Math.toDegrees(car.getBody().getAngle());
            ModelInstance instance = carriageInstances.get(i);
            setVehicleTransform(instance, pos.x, 0.0f, pos.y, yawDeg);
            rotateVehicleWheels(instance, cumulativeWheelRotationDeg);
            rotateTurretNode(instance, currentTurretYawDeg - yawDeg);
        }

        // 4. Sync active Raider 3D instances (RAMMER_BUGGY -> raider_buggy.obj, MORTAR_SKIFF -> raider_harpoon_rig.obj)
        raiderInstances.clear();
        if (combatSystem != null) {
            Array<TurretCombatSystem.RaiderTarget> raiders = combatSystem.getActiveRaiders();
            for (int i = 0; i < raiders.size; i++) {
                TurretCombatSystem.RaiderTarget r = raiders.get(i);
                if (r.isDestroyed()) {
                    continue;
                }
                String meshKey = (r.archetype == TurretCombatSystem.RaiderArchetype.MORTAR_SKIFF)
                        ? MESH_RAIDER_HARPOON_RIG
                        : MESH_RAIDER_BUGGY;
                ModelInstance raiderInst = new ModelInstance(loadedModels.get(meshKey));
                setVehicleTransform(raiderInst, r.position.x, 0.0f, r.position.y, 0.0f);
                rotateVehicleWheels(raiderInst, cumulativeWheelRotationDeg * 1.15f);
                rotateTurretNode(raiderInst, 180.0f);
                raiderInstances.add(raiderInst);
            }
        }

        // 5. Sync 3D Spline Track rail segments ahead and behind the locomotive
        if (trackSystem != null) {
            float baseTrackX = Math.max(0.0f, locoPos.x - 36.0f);
            for (int i = 0; i < trackRailInstances.size(); i++) {
                float sampleX = baseTrackX + i * 12.0f;
                SplineTrackSystem.TrackSample sample = trackSystem.obtainSample();
                try {
                    trackSystem.sampleAtDistance(sampleX, sample);
                    float trackZ = sample.position.y;
                    float tangentYawDeg = (float) Math.toDegrees(Math.atan2(sample.tangent.y, sample.tangent.x));
                    ModelInstance rail = trackRailInstances.get(i);
                    rail.transform.idt()
                            .translate(sampleX, -0.15f, trackZ)
                            .rotate(Vector3.Y, -tangentYawDeg);
                } finally {
                    trackSystem.freeSample(sample);
                }
            }
        }

        // 6. Update chase camera following the locomotive
        camera.position.set(locoPos.x - 22.0f, 18.5f, locoPos.y + 24.0f);
        camera.lookAt(locoPos.x + 8.0f, 1.8f, locoPos.y);
        camera.up.set(Vector3.Y);
        camera.update();
    }

    private static void setVehicleTransform(ModelInstance instance, float x, float y, float z, float yawDeg) {
        instance.transform.idt()
                .translate(x, y, z)
                .rotate(Vector3.Y, 90.0f - yawDeg);
        instance.calculateTransforms();
    }

    private static void rotateVehicleWheels(ModelInstance instance, float wheelPitchDeg) {
        String[] wheelNodeNames = {
            "wheel_fl", "wheel_fr", "wheel_ml", "wheel_mr", "wheel_rl", "wheel_rr"
        };
        for (String wheelId : wheelNodeNames) {
            Node wheel = instance.getNode(wheelId, true);
            if (wheel != null) {
                wheel.rotation.set(Vector3.X, wheelPitchDeg);
            }
        }
        instance.calculateTransforms();
    }

    private static void rotateTurretNode(ModelInstance instance, float turretYawDeg) {
        Node turretBase = instance.getNode("turret_base", true);
        if (turretBase != null) {
            turretBase.rotation.set(Vector3.Y, turretYawDeg);
            instance.calculateTransforms();
        }
    }

    public void render() {
        int activeModels = 1 + carriageInstances.size() + raiderInstances.size() + trackRailInstances.size();
        lastDrawCallModelCount = activeModels;
        renderedFrameCount++;

        if (!isOpenGlAvailable() || modelBatch == null) {
            return;
        }

        Gdx.gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.gl.glClearColor(0.84f, 0.68f, 0.46f, 1.0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        modelBatch.begin(camera);
        for (ModelInstance rail : trackRailInstances) {
            modelBatch.render(rail, environment);
        }
        modelBatch.render(locomotiveInstance, environment);
        for (ModelInstance carriage : carriageInstances) {
            modelBatch.render(carriage, environment);
        }
        for (ModelInstance raider : raiderInstances) {
            modelBatch.render(raider, environment);
        }
        modelBatch.end();
    }

    public void resize(int width, int height) {
        camera.viewportWidth = Math.max(1, width);
        camera.viewportHeight = Math.max(1, height);
        camera.update();
    }

    public PerspectiveCamera getCamera() { return camera; }
    public Environment getEnvironment() { return environment; }
    public DirectionalLight getDesertSunLight() { return desertSunLight; }

    public Map<String, ObjMeshMetadata> getAllMeshMetadata() {
        return Collections.unmodifiableMap(meshMetadataByFile);
    }

    public ObjMeshMetadata getLoadedMeshMetadata(String fileName) {
        return meshMetadataByFile.get(fileName);
    }

    public int getTotalVehicleTriangles() {
        int sum = 0;
        for (ObjMeshMetadata meta : meshMetadataByFile.values()) {
            sum += meta.getTriangleCount();
        }
        return sum;
    }

    public ModelInstance getLocomotiveInstance() { return locomotiveInstance; }
    public List<ModelInstance> getCarriageInstances() { return Collections.unmodifiableList(carriageInstances); }
    public List<ModelInstance> getRaiderInstances() { return Collections.unmodifiableList(raiderInstances); }
    public List<ModelInstance> getTrackRailInstances() { return Collections.unmodifiableList(trackRailInstances); }

    public Matrix4 getLocomotiveTransform() { return locomotiveInstance.transform; }
    public float getCumulativeWheelRotationDeg() { return cumulativeWheelRotationDeg; }
    public float getCurrentTurretYawDeg() { return currentTurretYawDeg; }
    public int getRenderedFrameCount() { return renderedFrameCount; }
    public int getLastDrawCallModelCount() { return lastDrawCallModelCount; }
    public boolean isDisposed() { return disposed; }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (modelBatch != null) {
            try { modelBatch.dispose(); } catch (Exception ignored) {}
            modelBatch = null;
        }
        for (Model model : loadedModels.values()) {
            try { model.dispose(); } catch (Exception ignored) {}
        }
        loadedModels.clear();
        if (trackRailModel != null) {
            try { trackRailModel.dispose(); } catch (Exception ignored) {}
            trackRailModel = null;
        }
    }
}
