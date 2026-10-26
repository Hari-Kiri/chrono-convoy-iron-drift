"""Chrono-Convoy: Iron Drift - Sprint 2 convoy formation preview render (Blender 4.3, headless).
Usage: blender -b -P tools/blender/render_convoy_scene.py
Assets are glTF (+Y up, +Z forward); Blender importer converts to Z-up, so asset forward(+Z) -> Blender -Y.
"""
import bpy, math, os, random, shutil
from mathutils import Vector

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
A3D = os.path.join(ROOT, "assets", "3d")
OUT = os.path.join(A3D, "convoy_formation_render.png")
OUT2 = os.path.join(ROOT, "client", "assets", "3d", "convoy_formation_render.png")
random.seed(7)

bpy.ops.wm.read_factory_settings(use_empty=True)
scn = bpy.context.scene

def imp(name, loc, yaw_deg, roll_deg=0.0):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(A3D, name + ".glb"))
    new = [o for o in bpy.data.objects if o not in before]
    root = bpy.data.objects.new(name + "_ROOT", None)
    scn.collection.objects.link(root)
    for o in new:
        if o.parent is None:
            o.parent = root
    root.location = loc
    root.rotation_euler = (0, math.radians(roll_deg), math.radians(yaw_deg))
    return {o.name.split(".")[0]: o for o in new}

def aim_turret(objs, yaw, pitch):
    for k, o in objs.items():
        if k.startswith("turret_base"):
            o.rotation_mode = "XYZ"; o.rotation_euler[1] += math.radians(yaw)  # glTF Y-up yaw (local)
        if k.startswith("turret_gun"):
            o.rotation_mode = "XYZ"; o.rotation_euler[0] -= math.radians(pitch)

# Convoy heading toward Blender -Y (yaw 0). Gaps from manifest bounds (loco len 8.7, carriages 7.5)
loco = imp("locomotive", (0, -9.2, 0), 0)
art = imp("carriage_artillery", (0, 0, 0), 0)
cargo = imp("carriage_cargo", (0, 8.2, 0), 0)
aim_turret(art, 70, 25)
buggy = imp("raider_buggy", (-9.0, -1.0, 0.8), -25, -12)
aim_turret(buggy, -40, 8)
harp = imp("raider_harpoon_rig", (7.0, -10.5, 0), 30)
aim_turret(harp, 45, 10)

def mat(name, col, rough=0.8, metal=0.0, emit=None, strength=0.0):
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*col, 1); b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emit:
        b.inputs["Emission Color"].default_value = (*emit, 1); b.inputs["Emission Strength"].default_value = strength
    return m

# Terrain: subdivided plane displaced by procedural noise, flattened near the rail bed
bpy.ops.mesh.primitive_plane_add(size=160, location=(0, 0, 0))
ground = bpy.context.object
bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.subdivide(number_cuts=120); bpy.ops.object.mode_set(mode="OBJECT")
from mathutils import noise
for v in ground.data.vertices:
    x, y = v.co.x, v.co.y
    h = noise.noise(Vector((x * 0.04, y * 0.04, 0))) * 3.5 + noise.noise(Vector((x * 0.15, y * 0.15, 1))) * 0.6
    fade = min(1.0, max(0.0, (abs(x) - 3.0) / 10.0))
    h = h * fade + (fade * abs(x) * 0.12 if x < 0 else 0)
    if abs(x - 7) < 4 and -16 < y < -5: h *= 0.2
    v.co.z = h - 0.05
for p in ground.data.polygons: p.use_smooth = True
gm = mat("Desert_Sand", (0.78, 0.52, 0.30), 0.95)
nt = gm.node_tree; tex = nt.nodes.new("ShaderNodeTexNoise"); tex.inputs["Scale"].default_value = 40
ramp = nt.nodes.new("ShaderNodeValToRGB")
ramp.color_ramp.elements[0].color = (0.62, 0.38, 0.20, 1); ramp.color_ramp.elements[1].color = (0.88, 0.63, 0.38, 1)
nt.links.new(tex.outputs["Fac"], ramp.inputs["Fac"]); nt.links.new(ramp.outputs["Color"], nt.nodes["Principled BSDF"].inputs["Base Color"])
ground.data.materials.append(gm)

# Rails + sleepers
iron = mat("Rail_Iron", (0.25, 0.22, 0.2), 0.35, 1.0)
wood = mat("Sleeper_Wood", (0.22, 0.13, 0.07), 0.9)
for x in (-0.72, 0.72):
    bpy.ops.mesh.primitive_cube_add(location=(x, 0, 0.08)); r = bpy.context.object
    r.scale = (0.05, 60, 0.08); r.data.materials.append(iron)
for i in range(-60, 61):
    bpy.ops.mesh.primitive_cube_add(location=(0, i * 1.0, 0.0)); s = bpy.context.object
    s.scale = (1.2, 0.14, 0.06); s.data.materials.append(wood)

# Rocks scattered via Geometry Nodes (distribute points + instance icosphere, random scale/rotation)
rockm = mat("Desert_Rock", (0.45, 0.28, 0.17), 0.9)
bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1, radius=1, location=(0, 0, -50)); rock = bpy.context.object
rock.data.materials.append(rockm); rock.hide_render = True
bpy.ops.mesh.primitive_plane_add(size=100, location=(0, 0, 0)); scat = bpy.context.object
for v in scat.data.vertices: v.co.z = 0
mod = scat.modifiers.new("GN_RockScatter", "NODES")
tree = bpy.data.node_groups.new("GN_RockScatter", "GeometryNodeTree")
tree.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
tree.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
N = tree.nodes; L = tree.links
gi = N.new("NodeGroupInput"); go = N.new("NodeGroupOutput")
dist = N.new("GeometryNodeDistributePointsOnFaces"); dist.inputs["Density"].default_value = 0.06; dist.inputs["Seed"].default_value = 3
pos = N.new("GeometryNodeInputPosition"); sep = N.new("ShaderNodeSeparateXYZ")
absx = N.new("ShaderNodeMath"); absx.operation = "ABSOLUTE"
lt = N.new("ShaderNodeMath"); lt.operation = "LESS_THAN"; lt.inputs[1].default_value = 4.0
dele = N.new("GeometryNodeDeleteGeometry"); dele.domain = "POINT"
inst = N.new("GeometryNodeInstanceOnPoints"); oi = N.new("GeometryNodeObjectInfo"); oi.inputs["Object"].default_value = rock
rs = N.new("FunctionNodeRandomValue"); rs.data_type = "FLOAT_VECTOR"; rs.inputs["Min"].default_value = (0.3, 0.3, 0.2); rs.inputs["Max"].default_value = (1.6, 1.4, 0.9)
rr = N.new("FunctionNodeRandomValue"); rr.data_type = "FLOAT_VECTOR"; rr.inputs["Max"].default_value = (6.28, 6.28, 6.28)
real = N.new("GeometryNodeRealizeInstances")
L.new(gi.outputs[0], dist.inputs["Mesh"]); L.new(pos.outputs[0], sep.inputs[0]); L.new(sep.outputs["X"], absx.inputs[0])
L.new(absx.outputs[0], lt.inputs[0]); L.new(dist.outputs["Points"], dele.inputs["Geometry"]); L.new(lt.outputs[0], dele.inputs["Selection"])
L.new(dele.outputs[0], inst.inputs["Points"]); L.new(oi.outputs["Geometry"], inst.inputs["Instance"])
L.new(rs.outputs[0], inst.inputs["Scale"]); L.new(rr.outputs[0], inst.inputs["Rotation"]); L.new(inst.outputs[0], real.inputs[0]); L.new(real.outputs[0], go.inputs[0])
mod.node_group = tree
# drop scattered rocks onto terrain: shrinkwrap-ish via a Z offset from the same noise is complex; use shrinkwrap modifier
sw = scat.modifiers.new("Snap", "SHRINKWRAP"); sw.target = ground; sw.wrap_method = "PROJECT"; sw.use_project_z = True; sw.use_negative_direction = True; sw.use_positive_direction = True

# Tracers / shells (Ember_Glow emissive)
glow = mat("Tracer_Glow", (1, 0.4, 0.1), 0.5, emit=(1.0, 0.45, 0.1), strength=12)
def tracer(a, b, rad=0.06):
    a, b = Vector(a), Vector(b); d = b - a
    bpy.ops.mesh.primitive_cylinder_add(radius=rad, depth=d.length, location=(a + b) / 2, vertices=8)
    t = bpy.context.object; t.rotation_mode = "QUATERNION"; t.rotation_quaternion = d.to_track_quat("Z", "Y"); t.data.materials.append(glow)
tracer((1.5, -0.5, 3.0), (5.5, -6.0, 5.0), 0.09)
tracer((1.8, 0.5, 3.1), (6.5, -4.5, 4.4), 0.05)
tracer((-6.3, 4.5, 2.4), (-2.0, 6.5, 2.2), 0.04)
tracer((5.6, -8.8, 2.8), (1.2, -7.0, 2.2), 0.03)  # harpoon cable
bpy.ops.mesh.primitive_uv_sphere_add(radius=0.5, location=(5.6, -6.2, 5.1)); bpy.context.object.data.materials.append(glow)

# World sky + lighting (golden hour)
w = bpy.data.worlds.new("Sky"); scn.world = w; w.use_nodes = True
sky = w.node_tree.nodes.new("ShaderNodeTexSky"); sky.sky_type = "NISHITA"
sky.sun_elevation = math.radians(12); sky.sun_rotation = math.radians(200); sky.air_density = 2.5; sky.dust_density = 4
w.node_tree.links.new(sky.outputs[0], w.node_tree.nodes["Background"].inputs[0])
w.node_tree.nodes["Background"].inputs[1].default_value = 0.35
sun = bpy.data.lights.new("Sun", "SUN"); sun.energy = 3.2; sun.color = (1.0, 0.72, 0.45); sun.angle = math.radians(2)
so = bpy.data.objects.new("Sun", sun); scn.collection.objects.link(so); so.rotation_euler = (math.radians(68), 0, math.radians(-130))
rim = bpy.data.lights.new("Rim", "AREA"); rim.energy = 6000; rim.size = 15; rim.color = (0.55, 0.7, 1.0)
ro = bpy.data.objects.new("Rim", rim); scn.collection.objects.link(ro); ro.location = (-12, -25, 12)
ro.rotation_euler = (Vector((0, 0, 1)) - ro.location).to_track_quat("-Z", "Y").to_euler()

# Camera 3/4 action angle
cd = bpy.data.cameras.new("Cam"); cd.lens = 35
cam = bpy.data.objects.new("Cam", cd); scn.collection.objects.link(cam); scn.camera = cam
cam.location = (24, 20, 13)
cam.rotation_euler = (Vector((-0.5, -1.5, 1.2)) - cam.location).to_track_quat("-Z", "Y").to_euler()

# Render: Cycles CPU
scn.render.engine = "CYCLES"; scn.cycles.device = "CPU"
scn.cycles.samples = 64; scn.cycles.use_adaptive_sampling = True; scn.cycles.use_denoising = True
try: scn.cycles.denoiser = "OPENIMAGEDENOISE"
except TypeError: scn.cycles.use_denoising = False
scn.cycles.max_bounces = 6
scn.render.resolution_x, scn.render.resolution_y, scn.render.resolution_percentage = 1920, 1080, 100
scn.view_settings.view_transform = "AgX"; scn.view_settings.look = "AgX - Medium High Contrast"
scn.view_settings.exposure = -0.6
scn.render.image_settings.file_format = "PNG"; scn.render.filepath = OUT
bpy.ops.render.render(write_still=True)
os.makedirs(os.path.dirname(OUT2), exist_ok=True); shutil.copy2(OUT, OUT2)
print("RENDERED", OUT, OUT2)
