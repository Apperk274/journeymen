import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
with open(os.path.join(REPO, "examples", "make_schematics.py"), encoding="utf-8") as source:
    exec(source.read().split("\nOUT = ")[0])

BOX_KINDS = ["stone", "cobblestone", "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
             "dark_oak_planks", "mangrove_planks", "cherry_planks", "bamboo_planks", "crimson_planks", "warped_planks",
             "stone_bricks", "mossy_stone_bricks", "bricks", "sandstone", "red_sandstone", "andesite", "diorite", "granite",
             "polished_andesite", "polished_diorite", "polished_granite", "cobbled_deepslate", "tuff", "calcite",
             "mud_bricks", "smooth_stone", "white_wool", "orange_wool", "lime_wool"]


def checker(width, height, length):
    return {(x, y, z): state("stone") if (x + y + z) % 2 == 0 else state("oak_planks")
            for x in range(width) for y in range(height) for z in range(length)}


def box(n=13, h=5):
    blocks, i = {}, 0
    for x in range(n):
        for y in range(h):
            for z in range(n):
                if x in (0, n - 1) or z in (0, n - 1) or y in (0, h - 1):
                    blocks[(x, y, z)] = state(BOX_KINDS[i % len(BOX_KINDS)])
                    i += 1
                else:
                    blocks[(x, y, z)] = state("air")
    return blocks


def restock():
    return {(x, y, z): state("stone") if y == 0 else state("oak_planks")
            for x in range(32) for y in range(2) for z in range(32)}


def skip():
    fire = state("fire", age=0, east=False, north=False, south=False, up=False, west=False)
    kinds = [state("stone"), state("stone"), fire, state("oak_planks"), state("oak_planks")]
    return {(x, 0, 0): kind for x, kind in enumerate(kinds)}


def hang():
    blocks = {(x, y, 0): state("bricks") for x in range(3) for y in range(3)}
    blocks[(1, 1, 1)] = state("wall_torch", facing="south")
    blocks[(0, 0, 2)] = state("scaffolding", bottom=False, distance=0, waterlogged=False)
    blocks[(0, 1, 2)] = state("sand")
    blocks[(2, 1, 2)] = state("wall_torch", facing="south")
    return blocks


def missing():
    blocks = {(x, y, z): state("stone_bricks") if y == 0 else state("oak_planks") for x in range(4) for y in range(2) for z in range(4)}
    blocks[(0, 1, 0)] = state("farmland", moisture=0)
    return blocks


def tfc_shelf():
    blocks = {(x, y, 0): state("bricks") for x in range(3) for y in range(3)}
    blocks[(1, 1, 1)] = "tfc:fire_brick_shelf[facing=north]"
    blocks[(2, 1, 2)] = "tfc:fire_brick_shelf[facing=south]"
    return blocks


def row():
    return {(x, 0, 0): state("oak_planks") for x in range(60)}


def soil():
    return {(x, y, z): state("dirt") for x in range(32, 64) for y in range(2) for z in range(32)}


def putback():
    blocks = {(x, 0, 0): state(BOX_KINDS[x]) for x in range(27)}
    blocks.update({(x, 0, z): state("terracotta") for x in range(27) for z in range(1, 8)})
    blocks.update({(x, 1, z): state(BOX_KINDS[(x + z) % 27]) for x in range(27) for z in range(8)})
    return blocks


def write_all(out):
    write_sponge_v3(os.path.join(out, "test_solid.schem"), (26, 4, 26), checker(26, 4, 26))
    write_sponge_v3(os.path.join(out, "test_fill.schem"), (52, 1, 52), checker(52, 1, 52))
    write_sponge_v3(os.path.join(out, "test_box.schem"), (13, 5, 13), box())
    write_sponge_v3(os.path.join(out, "test_restock.schem"), (32, 2, 32), restock())
    write_sponge_v3(os.path.join(out, "test_skip.schem"), (5, 1, 1), skip())
    write_sponge_v3(os.path.join(out, "test_hang.schem"), (3, 3, 3), hang())
    write_sponge_v3(os.path.join(out, "test_missing.schem"), (4, 2, 4), missing())
    write_sponge_v3(os.path.join(out, "test_row.schem"), (60, 1, 1), row())
    write_sponge_v3(os.path.join(out, "test_dig.schem"), (32, 2, 32), {})
    write_sponge_v3(os.path.join(out, "test_soil.schem"), (64, 2, 32), soil())
    write_sponge_v3(os.path.join(out, "test_putback.schem"), (27, 2, 8), putback())
    if "run-tfc" in out.replace("\\", "/"):
        write_sponge_v3(os.path.join(out, "test_tfc_shelf.schem"), (3, 3, 3), tfc_shelf())


targets = sys.argv[1:] or [os.path.join(REPO, d, "schematics") for d in ("neoforge/run", "neoforge/run-tfc", "fabric/runs/server")
                           if os.path.isdir(os.path.join(REPO, d))]
for target in targets:
    write_all(target)
    print("Wrote test schematics to " + target)
