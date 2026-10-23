"""Turn a grid of Minecraft blocks into PLY meshes + a Sionna/Mitsuba scene XML.

Coordinates: Minecraft is y-up, Sionna is z-up. A point (x, y, z) in Minecraft,
relative to the region's min corner, becomes (x, -z, y) in the scene. This is a
proper rotation, so face winding is preserved.
"""

from pathlib import Path

import numpy as np

from .materials import FIXED_THICKNESS_M, bsdf_xml


def mc_to_scene(p: np.ndarray) -> np.ndarray:
    """Minecraft coords (relative to region origin) -> Sionna scene coords. Works on [..., 3]."""
    return np.stack([p[..., 0], -p[..., 2], p[..., 1]], axis=-1)


def scene_to_mc(p: np.ndarray) -> np.ndarray:
    return np.stack([p[..., 0], p[..., 2], -p[..., 1]], axis=-1)


def _runs(faces: np.ndarray) -> np.ndarray:
    """Merge equal-material faces into rectangles.

    faces: int array [L, H, W], 0 = no face. Returns rows of (l, h0, h1, w0, w1, material),
    first merging runs along W, then stacking identical runs along H.
    """
    nz = faces != 0
    prev = np.zeros_like(faces)
    prev[..., 1:] = faces[..., :-1]
    nxt = np.zeros_like(faces)
    nxt[..., :-1] = faces[..., 1:]
    starts = np.argwhere(nz & (prev != faces))
    ends = np.argwhere(nz & (nxt != faces))  # same order as starts (C order), one end per start
    if len(starts) == 0:
        return np.zeros((0, 6), dtype=np.int64)
    sl, h, w0 = starts.T
    w1 = ends[:, 2] + 1
    m = faces[sl, h, w0]
    order = np.lexsort((h, m, w1, w0, sl))
    sl, h, w0, w1, m = sl[order], h[order], w0[order], w1[order], m[order]
    same_key = np.r_[False, (sl[1:] == sl[:-1]) & (w0[1:] == w0[:-1]) & (w1[1:] == w1[:-1]) & (m[1:] == m[:-1])]
    new = ~(same_key & np.r_[False, h[1:] == h[:-1] + 1])
    first = np.flatnonzero(new)
    last = np.r_[first[1:], len(h)] - 1
    return np.stack([sl[first], h[first], h[last] + 1, w0[first], w1[first], m[first]], axis=1)


# For each axis a: the two in-plane axes (b, c), and whether (a, b, c) is a cyclic order of (x, y, z).
_PLANES = {0: (1, 2, True), 1: (0, 2, False), 2: (0, 1, True)}


MAX_DEPTH = 8  # walls deeper than this many blocks all get the same (already opaque) thickness
_KEY = 16  # face key = material * _KEY + depth


def _depth(solid: np.ndarray, forward: bool) -> np.ndarray:
    """For each cell, how many solid cells in a row start there along axis 0 (+ if forward, - if not)."""
    d = np.zeros(solid.shape, np.int32)
    run = np.zeros(solid.shape[1:], np.int32)
    for i in range(len(solid) - 1, -1, -1) if forward else range(len(solid)):
        run = np.where(solid[i], run + 1, 0)
        d[i] = run
    return np.minimum(d, MAX_DEPTH)


def mesh_blocks(mat: np.ndarray) -> dict[tuple[int, int], np.ndarray]:
    """Quads between solid blocks and air, grouped by (material index, wall depth in blocks).

    mat: int array [X, Y, Z] in Minecraft axes, 0 = air. Faces between two solids are
    skipped, and so are faces on the outer boundary of the region (so the cut-off edge
    of the world doesn't become a giant wall). Depth is how many solid blocks sit behind
    the face, so a 3-block wall can be made 3x as thick as a 1-block wall.
    Returns {(material, depth): float32 [N, 4, 3]} quad corners in Minecraft coords
    relative to the region origin.
    """
    quads: dict[tuple[int, int], list[np.ndarray]] = {}
    for a, (b, c, cyclic) in _PLANES.items():
        m_a = np.moveaxis(mat, a, 0)  # moveaxis keeps the other two axes in order: [a, b, c]
        solid = m_a > 0
        lo, hi, s_lo, s_hi = m_a[:-1], m_a[1:], solid[:-1], solid[1:]
        back = _depth(solid, forward=False)[:-1]  # solid run ending at lo, going -a
        ahead = _depth(solid, forward=True)[1:]  # solid run starting at hi, going +a
        for faces, positive in (
            (np.where(s_lo & ~s_hi, lo * _KEY + back, 0), True),
            (np.where(~s_lo & s_hi, hi * _KEY + ahead, 0), False),
        ):
            rects = _runs(faces)
            if len(rects) == 0:
                continue
            sl, h0, h1, w0, w1, key = rects.T
            p = (sl + 1).astype(np.float32)
            corners = np.zeros((len(rects), 4, 3), np.float32)
            corners[:, :, a] = p[:, None]
            # order (h0,w0) (h1,w0) (h1,w1) (h0,w1) gives normal +a when (a,b,c) is cyclic
            corners[:, :, b] = np.stack([h0, h1, h1, h0], 1)
            corners[:, :, c] = np.stack([w0, w0, w1, w1], 1)
            if positive != cyclic:
                corners = corners[:, ::-1]
            for k in np.unique(key):
                quads.setdefault(divmod(int(k), _KEY), []).append(corners[key == k])
    return {k: np.concatenate(v) for k, v in quads.items()}


def write_ply(path: Path, quads: np.ndarray) -> None:
    verts = mc_to_scene(quads.reshape(-1, 3)).astype("<f4")
    n = len(quads)
    base = (np.arange(n) * 4)[:, None]
    tris = np.concatenate([base + [0, 1, 2], base + [0, 2, 3]]).astype("<i4")
    faces = np.zeros(len(tris), dtype=[("n", "u1"), ("i", "<i4", 3)])
    faces["n"] = 3
    faces["i"] = tris
    header = (
        "ply\nformat binary_little_endian 1.0\n"
        f"element vertex {len(verts)}\nproperty float x\nproperty float y\nproperty float z\n"
        f"element face {len(tris)}\nproperty list uchar int vertex_indices\nend_header\n"
    )
    with open(path, "wb") as f:
        f.write(header.encode())
        f.write(verts.tobytes())
        f.write(faces.tobytes())


def write_scene(
    out_dir: Path, mat: np.ndarray, names: list[str], frequency_hz: float, thickness_per_block_m: float
) -> tuple[Path, int, list[str]]:
    """Write scene.xml + meshes/*.ply. names[i] is the material for index i (index 0 unused).

    Sionna sets thickness per material, not per surface, so each material gets one copy per
    wall depth ("concrete-3" = faces of a 3-block-deep wall). A ray crossing a wall passes
    two faces, so each face carries half of depth * thickness_per_block_m.
    Returns (xml path, triangle count, warnings).
    """
    (out_dir / "meshes").mkdir(parents=True, exist_ok=True)
    bsdfs, shapes, warnings = [], [], set()
    triangles = 0
    for (mi, depth), q in sorted(mesh_blocks(mat).items()):
        name = f"{names[mi]}-{depth}"
        thickness = FIXED_THICKNESS_M.get(names[mi], depth * thickness_per_block_m / 2)
        xml, warn = bsdf_xml(names[mi], frequency_hz, thickness, name)
        bsdfs.append(xml)
        if warn:
            warnings.add(warn)
        write_ply(out_dir / "meshes" / f"{name}.ply", q)
        triangles += 2 * len(q)
        shapes.append(
            f'<shape type="ply" id="mesh-blocks-{name}"><string name="filename" value="meshes/{name}.ply"/>'
            f'<boolean name="face_normals" value="true"/><ref id="{name}" name="bsdf"/></shape>'
        )
    xml_path = out_dir / "scene.xml"
    xml_path.write_text('<scene version="2.1.0">\n  ' + "\n  ".join(bsdfs + shapes) + "\n</scene>\n")
    return xml_path, triangles, sorted(warnings)
