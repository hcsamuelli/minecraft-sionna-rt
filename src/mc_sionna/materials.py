"""Map Minecraft block IDs to Sionna radio materials.

Minecraft has no "material" property on blocks anymore (removed in 1.20), so this
is a hand-written table keyed on block-ID substrings. First match wins. Blocks
mapped to None are treated as air (radio-transparent).
"""

import re

from sionna.rt.radio_materials.itu import ITU_MATERIALS_PROPERTIES

# Rules are checked in order against the block path (the part after "minecraft:").
RULES: list[tuple[str, str | None]] = [
    # our own transmitter / receiver blocks are not geometry
    (r"^(transmitter|receiver)$", None),
    # things that are mostly air or too small to matter at radio wavelengths
    (
        r"leaves|sapling|flower|tulip|orchid|allium|bluet|daisy|poppy|dandelion|lilac|peony|rose_bush"
        r"|short_grass|tall_grass|fern|dead_bush|vine|lichen|torch|(?<!sea_)lantern|candle|rail|button"
        r"|pressure_plate|sign|banner|carpet|ladder|lever|tripwire|string|cobweb|^fire$|soul_fire|redstone_wire"
        r"|repeater|comparator|sugar_cane|bamboo$|kelp(?!_block)|seagrass|sea_pickle|coral(?!_block)|mushroom$"
        r"|wheat|carrots|potatoes|beetroots|melon_stem|pumpkin_stem|sweet_berry|cocoa|nether_wart(?!_block)|^snow$|scaffolding"
        r"|flower_pot|head|skull|end_rod|lightning_rod|chain$|bars$|pointed_dripstone|amethyst_cluster"
        r"|_bud$|moss_carpet|hanging_roots|spore_blossom|glow_lichen|frogspawn|lily_pad|azalea$"
        r"|fence(?!_gate)|_fence_gate",
        None,
    ),
    # ores are mostly rock
    (r"_ore$", "concrete"),
    # metal
    (
        r"iron_|gold_block|copper|netherite_block|anvil|cauldron|hopper|heavy_core|raw_iron_block"
        r"|raw_gold_block|raw_copper_block|bell$",
        "metal",
    ),
    # glass
    (r"glass", "glass"),
    # wood (planks, logs, stems, doors, crafted wooden blocks)
    (
        r"planks|_log$|_wood$|_stem$|hyphae|bamboo_block|bamboo_mosaic|bookshelf|crafting_table|chest$"
        r"|^(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak|bamboo|crimson|warped)_(stairs|slab)$"
        r"|barrel|_door$|_trapdoor$|composter|loom|cartography_table|fletching_table|smithing_table"
        r"|lectern|beehive|bee_nest|note_block|jukebox|mangrove_roots",
        "wood",
    ),
    # textiles
    (r"wool|_bed$", "carpet_tile"),
    # clay bricks
    (r"^bricks$|^brick_|mud_brick|nether_brick", "brick"),
    # polished / white stone that is close to marble
    (r"quartz|calcite", "marble"),
    # water and ice (custom materials below)
    (r"^water$|bubble_column", "water"),
    (r"ice$", "ice"),
    (r"snow_block|powder_snow", "snow"),
    # soil
    (r"sand$|gravel|suspicious_sand|suspicious_gravel", "very_dry_ground"),
    (r"^mud$|^clay$|packed_mud", "wet_ground"),
    (r"dirt|grass_block|podzol|mycelium|farmland|moss_block|soul_soil|soul_sand|rooted|nylium", "medium_dry_ground"),
    # everything stone-like
    (
        r"stone|cobble|deepslate|andesite|diorite|granite|tuff|basalt|blackstone|concrete|terracotta"
        r"|obsidian|bedrock|prismarine|purpur|netherrack|end_stone|dripstone_block|magma|sandstone"
        r"|_ore$|furnace|smoker",
        "concrete",
    ),
]

_COMPILED = [(re.compile(p), m) for p, m in RULES]

# What a block with collision falls back to when no rule matches.
DEFAULT_SOLID = "concrete"

# Materials Sionna has no ITU entry for: (relative permittivity, conductivity S/m).
# ponytail: frequency-independent values tuned for ~2-6 GHz; add a frequency_update_callback if
# you care about mmWave water/ice.
# Materials whose surfaces keep a fixed thickness no matter how deep the block run is:
# players build windows out of glass blocks, and real window glass is a few mm thick.
FIXED_THICKNESS_M = {"glass": 0.006}

CUSTOM = {
    "water": (77.0, 2.88),  # ITU-R P.527 at 3.5 GHz
    "ice": (3.15, 1e-4),
    "snow": (1.5, 1e-4),
}


def material_for(block_id: str, has_collision: bool, is_water: bool) -> tuple[str | None, bool]:
    """(radio material name or None for air, True if no rule matched and we guessed)."""
    path = block_id.split(":", 1)[-1]
    for pattern, material in _COMPILED:
        if pattern.search(path):
            # kelp, seagrass, waterlogged fences etc. are still water, not air pockets
            return ("water" if material is None and is_water else material), False
    if is_water:
        return "water", False
    return (DEFAULT_SOLID, True) if has_collision else (None, False)


def bsdf_xml(name: str, frequency_hz: float, thickness_m: float, xml_id: str) -> tuple[str, str | None]:
    """Mitsuba <bsdf> element for a material, plus a warning if values were extrapolated."""
    if name in CUSTOM:
        eps, sigma = CUSTOM[name]
        return _radio_material(xml_id, eps, sigma, thickness_m), None
    f_ghz = frequency_hz / 1e9
    ranges = ITU_MATERIALS_PROPERTIES[name]
    for lo, hi in ranges:
        if lo <= f_ghz <= hi:
            return (
                f'<bsdf type="itu-radio-material" id="{xml_id}">'
                f'<string name="type" value="{name}"/><float name="thickness" value="{thickness_m:.6g}"/></bsdf>'
            ), None
    # Outside every ITU range: evaluate the closest range's fit at the clamped frequency.
    (lo, hi), (a, b, c, d) = min(ranges.items(), key=lambda kv: min(abs(kv[0][0] - f_ghz), abs(kv[0][1] - f_ghz)))
    f = min(max(f_ghz, lo), hi)
    eps, sigma = a * f**b, c * f**d
    warning = f"{name}: ITU data covers {lo}-{hi} GHz, using its {f:g} GHz values at {f_ghz:g} GHz"
    return _radio_material(xml_id, eps, sigma, thickness_m), warning


def _radio_material(xml_id: str, eps: float, sigma: float, thickness_m: float) -> str:
    return (
        f'<bsdf type="radio-material" id="{xml_id}">'
        f'<float name="relative_permittivity" value="{eps:.6g}"/>'
        f'<float name="conductivity" value="{sigma:.6g}"/>'
        f'<float name="thickness" value="{thickness_m:.6g}"/></bsdf>'
    )
