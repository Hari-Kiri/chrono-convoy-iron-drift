"""[3D-01] Chrono-Convoy: Iron Drift - procedural convoy asset generator.
Run: blender -b -P tools/blender/generate_convoy_assets.py
Blender build space: forward = -Y, up = +Z. Exported: +Z forward, +Y up, +X right.
"""
import bpy, bmesh, json, math, os
from mathutils import Matrix, Vector

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "assets", "3d")
MAX_TRIS = 3500
os.makedirs(OUT, exist_ok=True)

MATS = {
    "Armor_RustSteel": ((0.45, 0.22, 0.10, 1), 0.8, 0.7),
    "Chassis_DarkIron": ((0.08, 0.08, 0.09, 1), 0.9, 0.55),
    "Desert_TanPaint": ((0.76, 0.62, 0.40, 1), 0.1, 0.8),
    "Brass_Trim": ((0.78, 0.58, 0.20, 1), 1.0, 0.3),
    "Rubber_Tire": ((0.03, 0.03, 0.03, 1), 0.0, 0.95),
    "Hazard_Copper": ((0.72, 0.35, 0.15, 1), 0.9, 0.4),
    "Ember_Glow": ((1.0, 0.35, 0.05, 1), 0.0, 0.5),
}

def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    for n, (c, m, r) in MATS.items():
        mat = bpy.data.materials.new(n); mat.use_nodes = True
        b = mat.node_tree.nodes["Principled BSDF"]
        b.inputs["Base Color"].default_value = c
        b.inputs["Metallic"].default_value = m
        b.inputs["Roughness"].default_value = r
        if n == "Ember_Glow":
            b.inputs["Emission Color"].default_value = c
            b.inputs["Emission Strength"].default_value = 3.0
        mat.diffuse_color = c

class Builder:
    """Accumulates primitives into one bmesh with per-face material slots."""
    def __init__(self):
        self.bm = bmesh.new(); self.mats = []
    def _slot(self, m):
        if m not in self.mats: self.mats.append(m)
        return self.mats.index(m)
    def _tag(self, geom, m):
        i = self._slot(m)
        for f in {f for v in geom for f in v.link_faces}: f.material_index = i
    def box(self, loc, size, m, rot=None):
        r = bmesh.ops.create_cube(self.bm, size=1.0,
            matrix=Matrix.Translation(loc) @ (rot or Matrix.Identity(4)) @ Matrix.Diagonal((*size, 1)))
        self._tag(r["verts"], m)
    def cyl(self, loc, radius, depth, m, axis="X", seg=10, r2=None):
        rot = {"X": Matrix.Rotation(math.pi/2, 4, "Y"), "Y": Matrix.Rotation(math.pi/2, 4, "X"), "Z": Matrix.Identity(4)}[axis]
        r = bmesh.ops.create_cone(self.bm, cap_ends=True, segments=seg, radius1=radius,
            radius2=radius if r2 is None else r2, depth=depth, matrix=Matrix.Translation(loc) @ rot)
        self._tag(r["verts"], m)
    def build(self, name, origin=(0, 0, 0), parent=None):
        bmesh.ops.translate(self.bm, verts=self.bm.verts, vec=-Vector(origin))
        me = bpy.data.meshes.new(name); self.bm.to_mesh(me); self.bm.free()
        for m in self.mats: me.materials.append(bpy.data.materials[m])
        ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
        ob.location = origin
        if parent:
            ob.parent = parent; ob.matrix_parent_inverse = parent.matrix_world.inverted()
            ob.location = Vector(origin) - parent.matrix_world.translation
        bpy.context.view_layer.update()
        return ob

def rivet_tree():
    """Geometry Nodes: instance rivet studs on a grid on both armor flanks."""
    if "GN_ArmorRivets" in bpy.data.node_groups: return bpy.data.node_groups["GN_ArmorRivets"]
    ng = bpy.data.node_groups.new("GN_ArmorRivets", "GeometryNodeTree")
    ng.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
    for n, t in (("Length", "NodeSocketFloat"), ("Height", "NodeSocketFloat"), ("HalfWidth", "NodeSocketFloat"),
                 ("CenterZ", "NodeSocketFloat"), ("CenterY", "NodeSocketFloat"), ("Count", "NodeSocketInt"), ("Material", "NodeSocketMaterial")):
        ng.interface.new_socket(n, in_out="INPUT", socket_type=t)
    ng.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    N = ng.nodes; L = ng.links
    gi = N.new("NodeGroupInput"); go = N.new("NodeGroupOutput")
    grid = N.new("GeometryNodeMeshGrid"); grid.inputs["Vertices Y"].default_value = 2
    L.new(gi.outputs["Count"], grid.inputs["Vertices X"])
    L.new(gi.outputs["Height"], grid.inputs["Size Y"]); L.new(gi.outputs["Length"], grid.inputs["Size X"])
    stud = N.new("GeometryNodeMeshCube"); stud.inputs["Size"].default_value = (0.07, 0.07, 0.07)
    join = N.new("GeometryNodeJoinGeometry")
    for side in (1, -1):
        tr = N.new("GeometryNodeTransform")
        # grid lies in XY; rotate so X->length(Y axis), Y->height(Z axis), offset to flank
        tr.inputs["Rotation"].default_value = (math.pi/2, 0, math.pi/2)
        comb = N.new("ShaderNodeCombineXYZ"); mul = N.new("ShaderNodeMath"); mul.operation = "MULTIPLY"
        mul.inputs[1].default_value = side
        L.new(gi.outputs["HalfWidth"], mul.inputs[0]); L.new(mul.outputs[0], comb.inputs["X"])
        L.new(gi.outputs["CenterY"], comb.inputs["Y"]); L.new(gi.outputs["CenterZ"], comb.inputs["Z"])
        L.new(comb.outputs[0], tr.inputs["Translation"]); L.new(grid.outputs["Mesh"], tr.inputs["Geometry"])
        iop = N.new("GeometryNodeInstanceOnPoints")
        L.new(tr.outputs[0], iop.inputs["Points"]); L.new(stud.outputs["Mesh"], iop.inputs["Instance"])
        L.new(iop.outputs[0], join.inputs[0])
    real = N.new("GeometryNodeRealizeInstances"); L.new(join.outputs[0], real.inputs[0])
    sm = N.new("GeometryNodeSetMaterial"); L.new(real.outputs[0], sm.inputs["Geometry"]); L.new(gi.outputs["Material"], sm.inputs["Material"])
    j2 = N.new("GeometryNodeJoinGeometry")
    L.new(sm.outputs[0], j2.inputs[0]); L.new(gi.outputs[0], j2.inputs[0]); L.new(j2.outputs[0], go.inputs[0])
    return ng

def apply_rivets(ob, length, height, half_w, cz, cy, count):
    if "Brass_Trim" not in [m.name for m in ob.data.materials if m]:
        ob.data.materials.append(bpy.data.materials["Brass_Trim"])
    mod = ob.modifiers.new("ArmorRivets", "NODES"); mod.node_group = rivet_tree()
    ids = {s.name: s.identifier for s in mod.node_group.interface.items_tree if s.in_out == "INPUT"}
    for k, v in (("Length", length), ("Height", height), ("HalfWidth", half_w), ("CenterZ", cz - ob.location.z),
                 ("CenterY", cy - ob.location.y), ("Count", count), ("Material", bpy.data.materials["Brass_Trim"])):
        mod[ids[k]] = v
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.select_all(action="DESELECT"); ob.select_set(True)
    bpy.ops.object.modifier_apply(modifier=mod.name)

def wheels(chassis, pos, r, w, hub="Brass_Trim"):
    for name, (x, y) in pos.items():
        b = Builder(); b.cyl((x, y, r), r, w, "Rubber_Tire", seg=12)
        b.cyl((x + math.copysign(w/2 + 0.02, x), y, r), r*0.45, 0.06, hub, seg=8)
        b.build(name, origin=(x, y, r), parent=chassis)

def turret(chassis, base_loc, gun_fn, base_r=0.45):
    b = Builder(); b.cyl(base_loc, base_r, 0.3, "Armor_RustSteel", axis="Z", seg=10)
    b.box((base_loc[0], base_loc[1], base_loc[2] + 0.25), (base_r*1.4, base_r*1.6, 0.25), "Desert_TanPaint")
    base = b.build("turret_base", origin=base_loc, parent=chassis)
    piv = (base_loc[0], base_loc[1], base_loc[2] + 0.3)
    g = Builder(); gun_fn(g, piv); g.build("turret_gun", origin=piv, parent=base)

# ---------------- vehicles (Blender space: front = -Y) ----------------
def locomotive():
    b = Builder(); L = 8.0
    b.box((0, 0, 0.75), (2.2, L, 0.4), "Chassis_DarkIron")
    b.cyl((0, -0.8, 1.9), 0.95, 5.0, "Armor_RustSteel", axis="Y", seg=14)           # boiler
    for i in range(6): b.cyl((0, -3.0 + i*0.9, 1.9), 1.0, 0.12, "Brass_Trim", axis="Y", seg=14)  # bands
    b.box((0, 2.6, 2.1), (2.3, 2.4, 2.4), "Desert_TanPaint")                        # cab
    b.box((0, 2.6, 3.4), (2.5, 2.6, 0.15), "Armor_RustSteel")
    b.box((0, 1.39, 2.4), (1.6, 0.05, 0.6), "Ember_Glow")                           # firebox glow
    b.cyl((0, -2.6, 3.2), 0.28, 1.2, "Chassis_DarkIron", axis="Z", r2=0.4)          # stacks
    b.cyl((0, -0.6, 3.0), 0.22, 0.9, "Chassis_DarkIron", axis="Z")
    b.cyl((0, -3.31, 1.9), 0.6, 0.05, "Ember_Glow", axis="Y", seg=12)               # headlamp/furnace
    b.cyl((0, -4.25, 0.75), 0.7, 0.9, "Hazard_Copper", axis="Y", seg=4, r2=0.2)     # cowcatcher prow
    for s in (-1, 1): b.box((s*1.15, -0.5, 1.2), (0.1, 6.0, 0.9), "Armor_RustSteel")  # skirt plates
    ch = b.build("chassis")
    apply_rivets(ch, 5.6, 0.6, 1.22, 1.2, -0.5, 12)
    pos = {}
    for tag, y in (("f", -2.6), ("m", 0.0), ("r", 2.6)):
        pos[f"wheel_{tag}l"] = (-0.95, y); pos[f"wheel_{tag}r"] = (0.95, y)
    wheels(ch, pos, 0.55, 0.3)
    return ch

def carriage(kind):
    b = Builder(); L = 7.0
    b.box((0, 0, 0.75), (2.2, L, 0.35), "Chassis_DarkIron")
    for s in (-1, 1): b.box((0, s*3.6, 0.75), (0.5, 0.3, 0.2), "Brass_Trim")        # couplers
    if kind == "cargo":
        b.box((0, 0, 1.9), (2.3, 6.4, 1.9), "Desert_TanPaint")
        for i in range(7): b.box((0, -3.0 + i*1.0, 1.9), (2.4, 0.12, 2.0), "Armor_RustSteel")  # ribs
        b.box((0, 0, 2.95), (2.1, 6.2, 0.2), "Armor_RustSteel")
        for s in (-1, 1): b.box((s*1.2, 0, 1.9), (0.04, 1.2, 1.4), "Hazard_Copper")  # doors
    else:
        b.box((0, 0, 1.25), (2.3, 6.6, 0.7), "Armor_RustSteel")
        for s in (-1, 1): b.box((0, s*2.6, 1.9), (2.2, 1.0, 0.6), "Desert_TanPaint")  # ammo lockers
    ch = b.build("chassis")
    apply_rivets(ch, 5.8, 0.5 if kind == "artillery" else 1.2, 1.2 if kind == "artillery" else 1.26,
                 1.25 if kind == "artillery" else 1.9, 0, 10)
    pos = {}
    for tag, y in (("f", -2.4), ("r", 2.4)):
        pos[f"wheel_{tag}l"] = (-0.95, y); pos[f"wheel_{tag}r"] = (0.95, y)
    wheels(ch, pos, 0.5, 0.3)
    if kind == "artillery":
        def gun(g, p):
            g.box((p[0], p[1], p[2] + 0.35), (1.2, 1.4, 0.6), "Armor_RustSteel")
            for s in (-0.3, 0.3):
                g.cyl((p[0] + s, p[1] - 1.8, p[2] + 0.35), 0.14, 2.6, "Chassis_DarkIron", axis="Y", seg=10)
                g.cyl((p[0] + s, p[1] - 3.1, p[2] + 0.35), 0.19, 0.3, "Brass_Trim", axis="Y", seg=10)
        turret(ch, (0, 0, 1.75), gun, base_r=0.8)
    return ch

def buggy():
    b = Builder()
    b.box((0, 0, 0.7), (1.6, 3.6, 0.35), "Chassis_DarkIron")
    b.cyl((0, -1.9, 0.75), 0.7, 0.6, "Hazard_Copper", axis="Y", seg=4, r2=0.3)     # ram prow
    b.box((0, 0.4, 1.1), (1.4, 1.4, 0.5), "Desert_TanPaint")
    for s in (-0.6, 0.6): b.box((s, 0.4, 1.7), (0.08, 1.2, 0.08), "Armor_RustSteel")  # roll cage
    b.box((0, 1.4, 1.1), (1.0, 0.6, 0.6), "Armor_RustSteel")                         # engine
    b.cyl((0.3, 1.8, 1.4), 0.08, 0.6, "Chassis_DarkIron", axis="Z")
    b.box((0, 1.71, 1.1), (0.6, 0.02, 0.3), "Ember_Glow")
    ch = b.build("chassis")
    apply_rivets(ch, 2.8, 0.2, 0.82, 0.7, 0, 6)
    wheels(ch, {"wheel_fl": (-0.95, -1.2), "wheel_fr": (0.95, -1.2), "wheel_rl": (-0.95, 1.3), "wheel_rr": (0.95, 1.3)}, 0.45, 0.35)
    def gun(g, p):
        g.box((p[0], p[1], p[2] + 0.15), (0.3, 0.4, 0.3), "Armor_RustSteel")
        g.cyl((p[0], p[1] - 0.6, p[2] + 0.15), 0.06, 1.0, "Chassis_DarkIron", axis="Y", seg=8)
    turret(ch, (0, 0.4, 1.9), gun, base_r=0.25)
    return ch

def harpoon():
    b = Builder()
    b.box((0, 0, 0.85), (2.4, 5.0, 0.5), "Chassis_DarkIron")
    b.box((0, -1.7, 1.6), (2.2, 1.4, 1.1), "Desert_TanPaint")                         # cab
    b.box((0, -2.45, 1.7), (1.8, 0.05, 0.4), "Ember_Glow")                           # slit
    b.cyl((0, -2.8, 0.8), 0.75, 0.8, "Armor_RustSteel", axis="Y", seg=4, r2=0.4)      # plow
    b.box((0, 0.9, 1.35), (2.3, 2.8, 0.5), "Armor_RustSteel")                         # bed
    b.cyl((0, 2.0, 1.85), 0.45, 1.6, "Brass_Trim", axis="X", seg=12)                  # winch drum
    for s in (-1, 0.5): b.cyl((s*0.9, -0.8, 2.4), 0.12, 1.4, "Chassis_DarkIron", axis="Z")  # exhausts
    ch = b.build("chassis")
    apply_rivets(ch, 4.4, 0.3, 1.22, 0.85, 0, 10)
    pos = {}
    for tag, y in (("f", -1.7), ("m", 0.2), ("r", 1.8)):
        pos[f"wheel_{tag}l"] = (-1.2, y); pos[f"wheel_{tag}r"] = (1.2, y)
    wheels(ch, pos, 0.6, 0.45, hub="Hazard_Copper")
    def gun(g, p):
        g.box((p[0], p[1], p[2] + 0.3), (0.7, 1.0, 0.5), "Armor_RustSteel")
        g.cyl((p[0], p[1] - 1.4, p[2] + 0.3), 0.12, 2.2, "Chassis_DarkIron", axis="Y", seg=8)
        g.cyl((p[0], p[1] - 2.75, p[2] + 0.3), 0.25, 0.5, "Hazard_Copper", axis="Y", seg=6, r2=0.0)  # harpoon tip
        for s in (-1, 1): g.box((p[0] + s*0.25, p[1] - 2.3, p[2] + 0.3), (0.05, 0.3, 0.4), "Brass_Trim")  # barbs
    turret(ch, (0, 0.6, 1.6), gun, base_r=0.55)
    return ch

VEHICLES = [
    ("locomotive", "Armored Steam-Diesel Locomotive", locomotive),
    ("carriage_artillery", "Artillery Turret Carriage", lambda: carriage("artillery")),
    ("carriage_cargo", "Armored Cargo Carriage", lambda: carriage("cargo")),
    ("raider_buggy", "Fast Buggy Raider", buggy),
    ("raider_harpoon_rig", "Heavy Harpoon Rig Raider", harpoon),
]

def to_export(v):  # Blender (-Y fwd, +Z up) -> glTF/OBJ (+Z fwd, +Y up)
    return (v.x, v.z, -v.y)

def finalize(objs):
    for ob in objs:
        me = ob.data
        bm = bmesh.new(); bm.from_mesh(me)
        bmesh.ops.triangulate(bm, faces=bm.faces)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(me); bm.free()
        if not me.uv_layers: me.uv_layers.new(name="UVMap")
        me.uv_layers[0].name = "UVMap"
        bpy.context.view_layer.objects.active = ob
        bpy.ops.object.select_all(action="DESELECT"); ob.select_set(True)
        bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
        bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.02)
        bpy.ops.object.mode_set(mode="OBJECT")
        me.update()

def node_info(ob):
    return {"name": ob.name, "parent": ob.parent.name if ob.parent else None,
            "pivot": list(map(lambda x: round(x, 4), to_export(ob.matrix_world.translation))),
            "local_translation": list(map(lambda x: round(x, 4), to_export(ob.matrix_local.translation))),
            "triangles": len(ob.data.polygons),
            "materials": [m.name for m in ob.data.materials if m],
            "children": [c.name for c in ob.children]}

def obj_bounds(path):
    mn = [1e9]*3; mx = [-1e9]*3
    with open(path) as f:
        for line in f:
            if line.startswith("v "):
                p = list(map(float, line.split()[1:4]))
                mn = [min(a, b) for a, b in zip(mn, p)]; mx = [max(a, b) for a, b in zip(mx, p)]
    return mn, mx

def main():
    manifest = {"project": "Chrono-Convoy: Iron Drift", "ticket": "3D-01", "generator": "tools/blender/generate_convoy_assets.py",
                "blender_version": bpy.app.version_string, "units": "meters", "max_triangles": MAX_TRIS,
                "coordinate_system": {"forward": "+Z", "up": "+Y", "right": "+X"},
                "geometry_nodes": "GN_ArmorRivets (GeometryNodeTree, applied before export)",
                "materials": list(MATS.keys()), "vehicles": []}
    for vid, name, fn in VEHICLES:
        reset(); ch = fn()
        objs = [o for o in bpy.data.objects if o.type == "MESH"]
        finalize(objs)
        bpy.ops.object.select_all(action="DESELECT")
        for o in objs: o.select_set(True)
        bpy.context.view_layer.objects.active = ch
        glb = os.path.join(OUT, vid + ".glb"); obj = os.path.join(OUT, vid + ".obj")
        bpy.ops.export_scene.gltf(filepath=glb, export_format="GLB", use_selection=True, export_yup=True,
                                  export_apply=True, export_materials="EXPORT", export_texcoords=True, export_normals=True)
        bpy.ops.wm.obj_export(filepath=obj, export_selected_objects=True, forward_axis="NEGATIVE_Z", up_axis="Y",
                              export_materials=True, export_triangulated_mesh=True, export_uv=True, export_normals=True)
        pts = [to_export(o.matrix_world @ v.co) for o in objs for v in o.data.vertices]
        mn = [min(p[i] for p in pts) for i in range(3)]; mx = [max(p[i] for p in pts) for i in range(3)]
        omn, omx = obj_bounds(obj)
        tris = sum(len(o.data.polygons) for o in objs); verts = sum(len(o.data.vertices) for o in objs)
        assert tris < MAX_TRIS, (vid, tris)
        assert all(abs(a - b) < 1e-3 for a, b in zip(mn + mx, omn + omx)), ("OBJ axis mismatch", vid, mn, mx, omn, omx)
        r = lambda l: [round(x, 4) for x in l]
        manifest["vehicles"].append({
            "id": vid, "name": name, "glb": vid + ".glb", "obj": vid + ".obj", "mtl": vid + ".mtl",
            "triangles": tris, "triangle_count": tris, "faces": tris, "face_count": tris,
            "vertices": verts, "vertex_count": verts, "max_triangles": MAX_TRIS, "within_budget": tris < MAX_TRIS,
            "coordinate_system": manifest["coordinate_system"],
            "bounds": {"min": r(mn), "max": r(mx), "size": r([b - a for a, b in zip(mn, mx)]), "dimensions": r([b - a for a, b in zip(mn, mx)])},
            "obj_bounds_verified": {"min": r(omn), "max": r(omx)},
            "pivot": {"root": "chassis", "position": [0.0, 0.0, 0.0], "note": "ground plane Y=0, centered X=0, front faces +Z"},
            "root_node": "chassis",
            "wheels": sorted(o.name for o in objs if o.name.startswith("wheel_")),
            "turret": {"base": "turret_base", "gun": "turret_gun"} if "turret_base" in bpy.data.objects else None,
            "hierarchy": node_info(ch), "nodes": [node_info(o) for o in sorted(objs, key=lambda o: o.name)],
            "materials": sorted({m.name for o in objs for m in o.data.materials if m}),
        })
        print(f"[3D-01] {vid}: tris={tris} bounds min={r(mn)} max={r(mx)}")
    manifest["assets"] = manifest["vehicles"]
    with open(os.path.join(OUT, "manifest.json"), "w") as f: json.dump(manifest, f, indent=2)
    print("[3D-01] manifest written")

main()
