"""Run Sionna RT on a block grid sent by the mod, and export everything."""

import base64
import hashlib
import json
import tempfile
import time
from pathlib import Path
from typing import Any

import numpy as np
import scipy.io
from sionna.rt import PathSolver, PlanarArray, Receiver, Transmitter, load_scene, subcarrier_frequencies

from .materials import material_for
from .scene import mc_to_scene, scene_to_mc, write_scene

DEFAULTS = {
    "frequency_ghz": 3.5,
    "max_depth": 4,
    "max_drawn_paths": 100,
    "diffraction": False,
    "diffuse": False,
    "refraction": True,
    # how much real material one block of wall stands for (1.0 = true 1 m blocks, which makes
    # every wall nearly opaque; 0.2 makes a 1-block wall act like a normal 20 cm wall)
    "thickness_per_block_m": 0.2,
    "bandwidth_mhz": 100.0,
    "num_subcarriers": 256,
}

_INTERACTIONS = {1: "reflection", 2: "diffuse", 4: "refraction", 8: "diffraction"}


def _flag(v) -> bool:
    return v if isinstance(v, bool) else str(v).strip().lower() in ("true", "1", "yes", "on")


def _bounces_inside_solid(mat: np.ndarray, start: np.ndarray, verts: np.ndarray, inter: np.ndarray) -> bool:
    """True if the path reflects off a face from inside a solid block.

    Sionna sees a wall as two thin sheets with empty space between them, so a ray that
    crossed one face can bounce off the wall's other faces from the inside. Those paths
    don't exist in reality. start: tx in Minecraft coords relative to the region origin;
    verts/inter: this path's bounce points (scene coords) and interaction types.
    """
    prev = start
    for v, kind in zip(verts, inter, strict=True):
        if kind == 0:
            break
        v = scene_to_mc(v)
        if kind & 3:  # specular or diffuse reflection
            i = np.floor((prev + v) / 2).astype(int)
            if np.all(i >= 0) and np.all(i < mat.shape) and mat[tuple(i)] > 0:
                return True
        prev = v
    return False


# The last scene loaded into Sionna, so live updates where only the radios moved skip the rebuild.
_cached: dict[str, Any] = {}


def _scene_for(mat: np.ndarray, names: list[str], origin: np.ndarray, freq: float, s: dict, out_dir: Path | None):
    """(scene, triangles, warnings, reused) for this block grid.

    Reuses the last scene when the materials, region and settings that shape the geometry are
    unchanged and nothing needs exporting. Otherwise writes PLY + XML (to out_dir, or a temp
    folder that's deleted once Sionna has read it) and loads that.
    """
    thickness, diffraction = float(s["thickness_per_block_m"]), _flag(s["diffraction"])
    key = hashlib.sha1(
        mat.tobytes() + repr((mat.shape, names, origin.tolist(), freq, thickness, diffraction)).encode()
    ).hexdigest()
    if out_dir is None and _cached.get("key") == key:
        return _cached["scene"], _cached["triangles"], _cached["warnings"], True
    with tempfile.TemporaryDirectory(prefix="mc-sionna-") as tmp:
        folder = out_dir if out_dir is not None else Path(tmp)
        folder.mkdir(parents=True, exist_ok=True)
        xml_path, triangles, warnings = write_scene(folder, mat, names, freq, thickness)
        # Diffraction needs shared mesh edges, so weld coincident corners when it's on.
        # ponytail: merged rectangles still leave T-junctions where no edge is found; split faces
        # at every block corner if diffraction ever matters.
        scene = load_scene(str(xml_path), merge_shapes=False, remove_duplicate_vertices=diffraction)
    scene.tx_array = PlanarArray(num_rows=1, num_cols=1, pattern="iso", polarization="V")
    scene.rx_array = PlanarArray(num_rows=1, num_cols=1, pattern="iso", polarization="V")
    _cached.update(key=key, scene=scene, triangles=triangles, warnings=warnings)
    return scene, triangles, warnings, False


def simulate(req: dict, out_dir: Path | None) -> dict:
    """Trace paths for one snapshot of the world. Writes scene + channel files only if out_dir is given."""
    t0 = time.perf_counter()
    s = DEFAULTS | req.get("settings", {})
    origin = np.array(req["origin"], dtype=np.int64)
    size = tuple(req["size"])
    blocks = np.frombuffer(base64.b64decode(req["blocks"]), "<u2").reshape(size)
    if not req["transmitters"] or not req["receivers"]:
        raise ValueError("need at least one transmitter and one receiver in range")

    # palette -> material index (0 = air)
    names: list[str] = [""]
    lut = np.zeros(len(req["palette"]), np.int32)
    guessed = []
    for i, p in enumerate(req["palette"]):
        name, guess = material_for(p["id"], p["collision"], p["water"])
        if guess:
            guessed.append(p["id"])
        if name is not None:
            if name not in names:
                names.append(name)
            lut[i] = names.index(name)
    mat = lut[blocks]

    freq = s["frequency_ghz"] * 1e9
    scene, triangles, warnings, reused = _scene_for(mat, names, origin, freq, s, out_dir)
    warnings = list(warnings)
    if guessed:
        warnings.append(f"no material rule for {', '.join(sorted(guessed))}; treated as concrete")

    # antennas sit at block centers; positions relative to the region origin
    tx_mc = np.array([d["pos"] for d in req["transmitters"]], np.float64) + 0.5
    rx_mc = np.array([d["pos"] for d in req["receivers"]], np.float64) + 0.5
    tx_sc = mc_to_scene(tx_mc - origin)
    rx_sc = mc_to_scene(rx_mc - origin)

    scene.frequency = freq
    scene.remove(list(scene.transmitters) + list(scene.receivers))
    for i, p in enumerate(tx_sc):
        scene.add(Transmitter(f"tx{i}", position=p.tolist()))
    for i, p in enumerate(rx_sc):
        scene.add(Receiver(f"rx{i}", position=p.tolist()))

    t_solve = time.perf_counter()
    paths = PathSolver()(
        scene,
        max_depth=int(s["max_depth"]),
        los=True,
        specular_reflection=True,
        diffuse_reflection=_flag(s["diffuse"]),
        refraction=_flag(s["refraction"]),
        diffraction=_flag(s["diffraction"]),
        synthetic_array=True,
    )
    solve_s = time.perf_counter() - t_solve

    # shapes with synthetic arrays: a [rx, 1, tx, 1, P, 1], tau/valid [rx, tx, P],
    # vertices [depth, rx, tx, P, 3], interactions [depth, rx, tx, P]
    a, tau = paths.cir(normalize_delays=False, out_type="numpy")
    a = a[:, 0, :, 0, :, 0]
    valid = np.array(paths.valid, dtype=bool)
    verts = np.array(paths.vertices)
    inter = np.array(paths.interactions)
    for r, t, k in np.argwhere(valid):
        if _bounces_inside_solid(mat, tx_mc[t] - origin, verts[:, r, t, k], inter[:, r, t, k]):
            valid[r, t, k] = False
    a = np.where(valid, a, 0)
    n_sc = int(s["num_subcarriers"])
    freqs = np.array(subcarrier_frequencies(n_sc, s["bandwidth_mhz"] * 1e6 / n_sc), np.float64)
    # same formula as Sionna's Paths.cfr, done here so the dropped paths stay dropped
    h = (a[..., None] * np.exp(-2j * np.pi * freqs * np.where(valid, tau, 0)[..., None])).sum(axis=2)

    gain = np.abs(a) ** 2
    links = []
    for r in range(len(rx_sc)):
        for t in range(len(tx_sc)):
            g, d = gain[r, t][valid[r, t]], tau[r, t][valid[r, t]]
            total = g.sum()
            if total > 0:
                mean = (g * d).sum() / total
                spread = np.sqrt((g * (d - mean) ** 2).sum() / total)
            links.append(
                {
                    "tx": t,
                    "rx": r,
                    "num_paths": int(len(g)),
                    "gain_db": float(10 * np.log10(total)) if total > 0 else None,
                    "delay_spread_ns": float(spread * 1e9) if total > 0 else None,
                    "first_delay_ns": float(d.min() * 1e9) if total > 0 else None,
                }
            )

    drawn = []
    idx = np.argwhere(valid)
    idx = idx[np.argsort(-gain[valid], kind="stable")][: int(s["max_drawn_paths"])]
    for r, t, k in idx:
        hops = [d for d in range(verts.shape[0]) if inter[d, r, t, k] != 0]
        pts = np.concatenate([tx_mc[t][None], scene_to_mc(verts[hops, r, t, k]) + origin, rx_mc[r][None]])
        drawn.append(
            {
                "tx": int(t),
                "rx": int(r),
                "gain_db": float(10 * np.log10(gain[r, t, k])),
                "delay_ns": float(tau[r, t, k] * 1e9),
                "points": np.round(pts, 4).tolist(),
                "interactions": [_INTERACTIONS.get(int(inter[d, r, t, k]), str(int(inter[d, r, t, k]))) for d in hops],
            }
        )

    export: dict[str, Any] = {
        "a": a.astype(np.complex64),
        "tau": tau.astype(np.float32),
        "valid": valid,
        "h_freq": h.astype(np.complex64),
        "subcarrier_offsets_hz": freqs,
        "frequency_hz": np.float64(freq),
        "vertices": verts.astype(np.float32),
        "interactions": inter.astype(np.uint32),
        "tx_positions": tx_sc,
        "rx_positions": rx_sc,
        "origin_mc": origin,
    }
    summary = {
        "export_dir": str(out_dir.resolve()) if out_dir is not None else None,
        "scene_reused": reused,
        "elapsed_s": round(time.perf_counter() - t0, 3),
        "solve_s": round(solve_s, 3),
        "triangles": triangles,
        "materials": names[1:],
        "links": links,
        "warnings": warnings,
    }
    if out_dir is None:
        return summary | {"paths": drawn}
    np.savez_compressed(out_dir / "channels.npz", **export)
    scipy.io.savemat(out_dir / "channels.mat", export, do_compression=True)
    (out_dir / "metadata.json").write_text(
        json.dumps(
            {
                **summary,
                "settings": s,
                "origin_mc": origin.tolist(),
                "size": list(size),
                "transmitters_mc": req["transmitters"],
                "receivers_mc": req["receivers"],
                "coordinates": "scene (x, y, z) = minecraft (x - ox, -(z - oz), y - oy); antennas at block centers",
                "channels": "a, tau: per-path baseband coefficients and absolute delays [rx, tx, path]; "
                "h_freq: frequency response [rx, tx, subcarrier] at frequency_hz + subcarrier_offsets_hz",
            },
            indent=2,
        )
    )
    (out_dir / "paths.json").write_text(json.dumps(drawn))
    return summary | {"paths": drawn}
