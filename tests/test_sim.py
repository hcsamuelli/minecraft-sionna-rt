import base64
import json

import numpy as np
from sionna.rt import load_scene

from mc_sionna.scene import mesh_blocks
from mc_sionna.sim import simulate


def test_mesh_merges_faces_and_measures_depth():
    mat = np.zeros((5, 5, 5), int)
    mat[1:3, 2, 2] = 1  # two blocks side by side -> one box, 6 quads
    mat[1:4, 4, 1:4] = 2  # 3-wide slab at the top boundary: top face is skipped
    q = mesh_blocks(mat)
    assert sum(len(v) for (m, _), v in q.items() if m == 1) == 6
    assert (1, 2) in q and (1, 1) in q  # faces across the 2-block length see depth 2, the sides depth 1
    assert (2, 1) in q and not any(m == 2 and d > 3 for m, d in q)


def _world():
    """30 x 12 x 30 world: grass floor at y=0, stone wall at x=15 with a glass window."""
    sx, sy, sz = 30, 12, 30
    palette = [
        {"id": "minecraft:air", "collision": False, "water": False},
        {"id": "minecraft:grass_block", "collision": True, "water": False},
        {"id": "minecraft:stone", "collision": True, "water": False},
        {"id": "minecraft:glass", "collision": True, "water": False},
        {"id": "mc_sionna:transmitter", "collision": True, "water": False},
        {"id": "mc_sionna:receiver", "collision": True, "water": False},
    ]
    g = np.zeros((sx, sy, sz), "<u2")
    g[:, 0, :] = 1
    g[15, 1:8, 5:25] = 2
    g[15, 3:5, 12:16] = 3
    g[5, 3, 14], g[25, 3, 14] = 4, 5
    return {
        "origin": [100, 60, -40],
        "size": [sx, sy, sz],
        "palette": palette,
        "blocks": base64.b64encode(g.tobytes()).decode(),
        "transmitters": [{"pos": [105, 63, -26]}],
        "receivers": [{"pos": [125, 63, -26]}, {"pos": [108, 63, -10]}],
        "settings": {"max_depth": 3},
    }


def test_simulate_end_to_end(tmp_path):
    res = simulate(_world(), tmp_path / "run")
    out = tmp_path / "run"
    assert res["triangles"] > 0
    assert {"glass", "concrete", "medium_dry_ground"} <= set(res["materials"])
    # receiver behind the window still hears the transmitter; the open-air one does too
    assert all(link["gain_db"] is not None for link in res["links"])
    behind, open_air = res["links"]
    assert open_air["gain_db"] > behind["gain_db"]
    # drawn paths start at the tx block center and end at an rx block center
    p = res["paths"][0]
    assert p["points"][0] == [105.5, 63.5, -25.5]
    assert p["points"][-1] in ([125.5, 63.5, -25.5], [108.5, 63.5, -9.5])
    assert any("reflection" in q["interactions"] for q in res["paths"])
    # exports: channels with the right shapes, and the scene loads on its own in Sionna
    ch = np.load(out / "channels.npz")
    assert ch["a"].shape[:2] == (2, 1) and ch["h_freq"].shape == (2, 1, 256)
    assert (out / "channels.mat").exists()
    assert json.loads((out / "metadata.json").read_text())["origin_mc"] == [100, 60, -40]
    assert len(load_scene(str(out / "scene.xml")).objects) > 0


def test_no_paths_bounce_inside_walls(tmp_path):
    # 1-block stone wall in open air (no ground): only the straight crossing is real
    sx, sy, sz = 30, 12, 30
    g = np.zeros((sx, sy, sz), "<u2")
    g[15, 1:10, 3:27] = 1
    req = {
        "origin": [0, 0, 0],
        "size": [sx, sy, sz],
        "palette": [
            {"id": "minecraft:air", "collision": False, "water": False},
            {"id": "minecraft:stone", "collision": True, "water": False},
        ],
        "blocks": base64.b64encode(g.tobytes()).decode(),
        "transmitters": [{"pos": [5, 4, 14]}],
        "receivers": [{"pos": [25, 4, 14]}],
        "settings": {"max_depth": 4, "diffraction": "False"},  # string "False" must mean off
    }
    link = simulate(req, tmp_path / "wall")["links"][0]
    assert link["num_paths"] == 1 and link["delay_spread_ns"] < 0.01


def test_material_rules():
    from mc_sionna.materials import material_for

    assert material_for("minecraft:sea_lantern", True, False)[0] == "concrete"
    assert material_for("minecraft:dried_kelp_block", True, False) == ("concrete", True)
    assert material_for("minecraft:heavy_core", True, False)[0] == "metal"
    assert material_for("minecraft:kelp", False, True)[0] == "water"
    assert material_for("minecraft:oak_leaves", True, False)[0] is None
