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


def redo():
    blocks = {(x, 0, z): state("oak_planks") if x % 2 == 0 else state("spruce_planks") for x in range(16) for z in range(16)}
    blocks.update({(x, y, z): state("white_wool") for x in range(16) for y in (1, 2) for z in range(16)})
    blocks.update({(x, 3, z): state("air") for x in range(16) for z in range(16)})
    return blocks


def made(path="dirt_path", farmland=state("farmland", moisture=0), dirt="stone"):
    blocks = {(x, y, z): state("air") for x in range(7) for y in range(2) for z in range(3)}
    for z in range(3):
        blocks[(0, 0, z)] = state(path)
        blocks[(0, 1, z)] = state(dirt)
        blocks[(2, 0, z)] = farmland
        blocks[(2, 1, z)] = state(dirt)
        blocks[(4, 0, z)] = state(path)
    return blocks


def sec_decor():
    click = '{"text":"Click me","color":"red","clickEvent":{"action":"run_command","value":"/op Mallory"}}'
    hover = '{"text":"Hover","hoverEvent":{"action":"show_text","contents":"x"},"insertion":"/op Mallory"}'
    selector = '{"selector":"@a"}'
    nbt = '{"nbt":"Pos","entity":"@p"}'
    score = '{"score":{"name":"@p","objective":"x"}}'
    back = '{"text":"Back","clickEvent":{"action":"run_command","value":"/op Mallory"}}'
    text = {"messages": [click, hover, selector, nbt], "color": "black", "has_glowing_text": Byte(0)}
    back_text = {"messages": [back, score, '""', '""'], "color": "black", "has_glowing_text": Byte(0)}
    book = {"id": "minecraft:written_book", "count": 1, "components": {"minecraft:written_book_content": {
        "title": {"raw": "Rules"}, "author": "Mallory", "resolved": Byte(0),
        "pages": [{"raw": click}, {"raw": selector}]}}}
    blocks = {(x, 0, z): state("stone") for x in range(5) for z in range(3)}
    blocks[(0, 1, 1)] = state("oak_sign", rotation=0, waterlogged=False)
    blocks[(2, 1, 1)] = state("lectern", facing="north", has_book=True, powered=False)
    blocks[(4, 2, 1)] = state("stone")
    blocks[(4, 1, 1)] = state("oak_hanging_sign", attached=False, rotation=0, waterlogged=False)
    entities = {
        (0, 1, 1): {"id": "minecraft:sign", "is_waxed": Byte(0), "front_text": text, "back_text": back_text},
        (2, 1, 1): {"id": "minecraft:lectern", "Book": book, "Page": 0},
        (4, 1, 1): {"id": "minecraft:hanging_sign", "is_waxed": Byte(0), "front_text": text, "back_text": back_text},
    }
    return blocks, entities


def write_sec_huge(path):
    write(path, {"Schematic": {
        "Version": 3, "DataVersion": DATA_VERSION, "Width": Short(1024), "Height": Short(256), "Length": Short(1024),
        "Blocks": {"Palette": {"minecraft:stone": 0}, "Data": ByteArray(bytes(1)), "BlockEntities": []},
    }})


def write_sec_bloat(path):
    write(path, {"Schematic": {
        "Version": 3, "DataVersion": DATA_VERSION, "Width": Short(1), "Height": Short(1), "Length": Short(1),
        "Blocks": {"Palette": {"minecraft:stone": 0}, "Data": ByteArray(bytes(1)), "BlockEntities": []},
        "Padding": ByteArray(bytes(140 * 1024 * 1024)),
    }})


def edge_void():
    return {(0, 0, 0): state("stone"), (1, 0, 0): state("structure_void"), (2, 0, 0): state("stone")}


def write_edge_made_big(path, n=256):
    palette = {"minecraft:stone": 0}
    data = bytearray(n * n * n)
    for x in range(9):
        block = state("farmland", moisture=x) if x < 8 else state("dirt_path")
        for y in (0, n - 1):
            data[x + y * n * n] = palette.setdefault(block, len(palette))
    write(path, {"Schematic": {
        "Version": 3, "DataVersion": DATA_VERSION, "Width": Short(n), "Height": Short(n), "Length": Short(n),
        "Blocks": {"Palette": palette, "Data": ByteArray(bytes(data)), "BlockEntities": []},
    }})


def write_edge_v1(path):
    palette, data = sponge_data((2, 2, 1), {(0, 0, 0): "minecraft:stone", (1, 0, 0): "minecraft:stone",
                                            (0, 1, 0): "minecraft:sign[rotation=0,waterlogged=false]"})
    sign = {"Pos": IntArray([0, 1, 0]), "Id": "minecraft:sign", "Text1": '{"text":"V1"}', "Text2": '{"text":""}',
            "Text3": '{"text":""}', "Text4": '{"text":""}'}
    write(path, {
        "Version": 1, "Width": Short(2), "Height": Short(2), "Length": Short(1), "Offset": IntArray([1000, 64, 1000]),
        "Metadata": {"WEOffsetX": -1, "WEOffsetY": 0, "WEOffsetZ": -2},
        "PaletteMax": len(palette), "Palette": palette, "BlockData": data, "TileEntities": [sign],
    }, "Schematic")


LECTERN_BOOK = {"id": "minecraft:lectern", "Book": {"id": "minecraft:writable_book", "count": 1}, "Page": 0}


def write_edge_v2_tile(path):
    palette, data = sponge_data((1, 1, 1), {(0, 0, 0): state("lectern", facing="north", has_book=True, powered=False)})
    lectern = dict({k: v for k, v in LECTERN_BOOK.items() if k != "id"}, Pos=IntArray([0, 0, 0]), Id="minecraft:lectern")
    write(path, {
        "Version": 2, "DataVersion": DATA_VERSION, "Width": Short(1), "Height": Short(1), "Length": Short(1),
        "PaletteMax": len(palette), "Palette": palette, "BlockData": data, "TileEntities": [lectern],
    }, "Schematic")


def write_edge_overlap(path):
    full = state("lectern", facing="north", has_book=True, powered=False)
    empty = state("lectern", facing="north", has_book=False, powered=False)
    regions = {
        "a": litematic_region((0, 0, 0), (2, 1, 1), {(0, 0, 0): full, (1, 0, 0): state("stone")}, {(0, 0, 0): LECTERN_BOOK}),
        "b": litematic_region((0, 0, 0), (2, 1, 1), {(0, 0, 0): empty, (1, 0, 0): "journeymen_test:nothing"}),
    }
    write_litematic(path, "overlap", regions, (2, 1, 1))


def write_edge_dupes(path):
    palette = [parse_state(state("lectern", facing="north", has_book=True, powered=False)),
               parse_state(state("lectern", facing="north", has_book=False, powered=False)),
               parse_state(state("stone")), {"Name": "journeymen_test:nothing"}]
    blocks = [{"pos": [0, 0, 0], "state": 0, "nbt": LECTERN_BOOK}, {"pos": [0, 0, 0], "state": 1},
              {"pos": [1, 0, 0], "state": 2}, {"pos": [1, 0, 0], "state": 3}]
    write(path, {"DataVersion": DATA_VERSION, "size": [2, 1, 1], "palette": palette, "blocks": blocks, "entities": []})


def edge_live(width, kind="oak_planks"):
    return {(x, 0, z): state(kind) for x in range(width) for z in range(8)}


def fl_into():
    blocks = {(x, 0, z): state("glass") for x in range(5) for z in range(3)}
    blocks[(0, 0, 0)] = state("stone")
    blocks[(2, 0, 0)] = state("stone")
    blocks[(4, 0, 0)] = state("oak_slab", type="bottom", waterlogged=False)
    blocks[(0, 0, 2)] = state("oak_stairs", facing="north", half="bottom", shape="straight", waterlogged=True)
    return blocks


def fl_air():
    blocks = {(x, 0, 0): state("air") for x in range(5)}
    blocks[(0, 0, 0)] = state("stone")
    blocks[(3, 0, 0)] = state("glass")
    return blocks


def fl_wet():
    blocks = {(x, y, z): state("stone") if y == 0 else state("air") for x in range(13) for y in range(3) for z in range(3)}
    blocks[(1, 0, 1)] = state("water", level=0)
    blocks[(1, 1, 1)] = state("lily_pad")
    blocks[(2, 0, 1)] = state("sand")
    blocks[(2, 1, 1)] = state("sugar_cane", age=0)
    blocks[(5, 0, 1)] = state("dirt")
    blocks[(5, 1, 1)] = state("small_dripleaf", facing="north", half="lower", waterlogged=True)
    blocks[(5, 2, 1)] = state("small_dripleaf", facing="north", half="upper", waterlogged=False)
    blocks[(8, 1, 1)] = state("tube_coral_fan", waterlogged=True)
    for x, z in ((4, 1), (6, 1), (5, 0), (5, 2), (7, 1), (9, 1), (8, 0), (8, 2)):
        blocks[(x, 1, z)] = state("stone")
    blocks[(10, 0, 1)] = state("tube_coral_block")
    blocks[(11, 0, 1)] = state("water", level=0)
    return blocks


def fl_drain():
    return {(0, 0, 0): state("oak_slab", type="bottom", waterlogged=False),
            (1, 0, 0): state("oak_stairs", facing="north", half="bottom", shape="straight", waterlogged=True)}


def ph_fall():
    return {(0, 0, 0): state("stone"), (0, 1, 0): state("sand"), (1, 0, 0): state("air"), (1, 1, 0): state("sand"),
            (2, 0, 0): state("structure_void"), (2, 1, 0): state("gravel"), (3, 0, 0): state("gravel"), (3, 1, 0): state("air")}


def ph_tnt():
    kinds = [state("redstone_block"), state("tnt", unstable=False), state("stone"), state("tnt", unstable=False), state("stone")]
    return {(x, 0, 0): kind for x, kind in enumerate(kinds)}


def st_cauldron():
    return {(0, 0, 0): state("water_cauldron", level=3), (2, 0, 0): state("water_cauldron", level=1),
            (4, 0, 0): state("lava_cauldron"), (6, 0, 0): state("powder_snow_cauldron", level=3)}


def st_items():
    blocks = {(x, 0, z): state("air") for x in range(13) for z in range(2)}
    blocks[(0, 0, 1)] = state("respawn_anchor", charges=4)
    blocks[(2, 0, 1)] = state("respawn_anchor", charges=2)
    blocks[(4, 0, 1)] = state("end_portal_frame", facing="north", eye=True)
    blocks[(6, 0, 1)] = state("candle", candles=3, lit=True, waterlogged=False)
    blocks[(8, 0, 1)] = state("stone")
    blocks[(8, 0, 0)] = state("glow_lichen", south=True, down=True, north=False, east=False, west=False, up=False, waterlogged=False)
    blocks[(10, 0, 1)] = state("stone")
    blocks[(11, 0, 0)] = state("stone")
    blocks[(10, 0, 0)] = state("vine", south=True, east=True, north=False, west=False, up=False)
    blocks[(12, 0, 1)] = state("powder_snow")
    return blocks


def st_rails():
    blocks = {(x, 0, z): state("air") for x in range(6) for z in range(5)}
    for z in range(5):
        blocks[(0, 0, z)] = state("rail", shape="north_south", waterlogged=False)
        blocks[(1, 0, z)] = state("rail", shape="north_south", waterlogged=False)
        blocks[(3, 0, z)] = state("rail", shape="north_south", waterlogged=False)
    blocks[(4, 0, 2)] = state("rail", shape="east_west", waterlogged=False)
    blocks[(5, 0, 2)] = state("rail", shape="east_west", waterlogged=False)
    return blocks


def gr_grow():
    blocks = {(x, y, z): state("air") for x in range(12) for y in range(7) for z in range(3)}
    blocks[(3, 0, 1)] = state("dirt")
    blocks[(3, 1, 1)] = state("oak_sapling", stage=0)
    blocks[(7, 0, 1)] = state("sand")
    blocks[(7, 1, 1)] = state("sugar_cane", age=0)
    blocks[(8, 0, 1)] = state("water", level=0)
    for x, z in ((8, 0), (8, 2), (9, 1)):
        blocks[(x, 0, z)] = state("stone")
    blocks[(11, 0, 1)] = state("dirt")
    return blocks


def gr_tree():
    blocks = {(x, y, z): state("air") for x in range(26) for y in range(7) for z in range(3)}
    blocks[(3, 0, 1)] = state("dirt")
    blocks[(3, 1, 1)] = state("oak_sapling", stage=0)
    for x in range(10, 26):
        blocks[(x, 2, 2)] = state("stone")
        blocks[(x, 2, 1)] = state("wall_torch", facing="north")
    return blocks


def gr_farm():
    blocks = {(x, y, z): state("air") for x in range(30) for y in range(3) for z in range(3)}
    blocks[(0, 0, 1)] = state("farmland", moisture=0)
    blocks[(3, 0, 1)] = state("stone")
    blocks[(3, 1, 1)] = state("water", level=0)
    for x, z in ((2, 1), (4, 1), (3, 0), (3, 2)):
        blocks[(x, 1, z)] = state("stone")
    for x in range(6, 30):
        blocks[(x, 2, 0)] = state("stone")
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
    write_sponge_v3(os.path.join(out, "test_redo.schem"), (16, 4, 16), redo())
    write_sponge_v3(os.path.join(out, "test_made.schem"), (7, 2, 3), made())
    write_structure(os.path.join(out, "test_sec_decor.nbt"), (5, 3, 3), *sec_decor())
    write_structure(os.path.join(out, "test_sec_empty.nbt"), (0, 0, 0), {})
    write_sec_huge(os.path.join(out, "test_sec_huge.schem"))
    write_sec_bloat(os.path.join(out, "test_sec_bloat.schem"))
    write_sponge_v3(os.path.join(out, "test_edge_void.schem"), (3, 1, 1), edge_void())
    write_litematic(os.path.join(out, "test_edge_void.litematic"), "void", {"void": litematic_region((0, 0, 0), (3, 1, 1), edge_void())}, (3, 1, 1))
    write_mcedit(os.path.join(out, "test_edge_void.schematic"), (3, 1, 1), {(0, 0, 0): (1, 0), (1, 0, 0): (217, 0), (2, 0, 0): (1, 0)})
    write_structure(os.path.join(out, "test_edge_void.nbt"), (3, 1, 1), edge_void())
    write_edge_made_big(os.path.join(out, "test_edge_made_big.schem"))
    write_edge_v1(os.path.join(out, "test_edge_v1.schem"))
    write_edge_v2_tile(os.path.join(out, "test_edge_v2_tile.schem"))
    write_edge_overlap(os.path.join(out, "test_edge_overlap.litematic"))
    write_edge_dupes(os.path.join(out, "test_edge_dupes.nbt"))
    write_sponge_v3(os.path.join(out, "test_edge_tall.schem"), (1, 3000, 1), {(0, 0, 0): state("stone")}, offset=(40000000, -5000, -40000000))
    write_sponge_v3(os.path.join(out, "test_edge_live_a.schem"), (8, 1, 8), edge_live(8))
    write_sponge_v3(os.path.join(out, "test_edge_live_b.schem"), (9, 1, 8), edge_live(9))
    write_sponge_v3(os.path.join(out, "test_edge_same_b.schem"), (8, 1, 8), edge_live(8, "spruce_planks"))
    write_sponge_v3(os.path.join(out, "test_fl_into.schem"), (5, 1, 3), fl_into())
    write_sponge_v3(os.path.join(out, "test_fl_ice.schem"), (3, 2, 1), {(x, y, 0): state("stone") if y == 0 else state("air")
                                                                          for x in range(3) for y in range(2)})
    write_sponge_v3(os.path.join(out, "test_fl_air.schem"), (5, 1, 1), fl_air())
    write_sponge_v3(os.path.join(out, "test_fl_flowing.schem"), (1, 1, 1), {(0, 0, 0): state("water", level=1)})
    write_sponge_v3(os.path.join(out, "test_fl_wet.schem"), (13, 3, 3), fl_wet())
    write_sponge_v3(os.path.join(out, "test_fl_cell.schem"), (1, 1, 1), {(0, 0, 0): state("water", level=0)})
    write_sponge_v3(os.path.join(out, "test_fl_drain.schem"), (2, 1, 1), fl_drain())
    write_sponge_v3(os.path.join(out, "test_ph_fall.schem"), (4, 2, 1), ph_fall())
    write_sponge_v3(os.path.join(out, "test_ph_above.schem"), (2, 1, 1), {})
    write_sponge_v3(os.path.join(out, "test_ph_tnt.schem"), (5, 1, 1), ph_tnt())
    write_sponge_v3(os.path.join(out, "test_st_cauldron.schem"), (7, 1, 1), st_cauldron())
    write_sponge_v3(os.path.join(out, "test_st_snow_part.schem"), (1, 1, 1), {(0, 0, 0): state("powder_snow_cauldron", level=2)})
    write_sponge_v3(os.path.join(out, "test_st_items.schem"), (13, 1, 2), st_items())
    write_sponge_v3(os.path.join(out, "test_st_rails.schem"), (6, 1, 5), st_rails())
    write_sponge_v3(os.path.join(out, "test_st_one.schem"), (1, 1, 1), {(0, 0, 0): state("stone")})
    write_sponge_v3(os.path.join(out, "test_st_air.schem"), (1, 1, 1), {})
    write_sponge_v3(os.path.join(out, "test_gr_grow.schem"), (12, 7, 3), gr_grow())
    write_sponge_v3(os.path.join(out, "test_gr_tree.schem"), (26, 7, 3), gr_tree())
    write_sponge_v3(os.path.join(out, "test_gr_farm.schem"), (30, 3, 3), gr_farm())
    write_sponge_v3(os.path.join(out, "test_mv_dig.schem"), (4, 2, 4), {})
    write_sponge_v3(os.path.join(out, "test_mv_pit.schem"), (9, 4, 9), {(x, 0, z): state("stone") for x in range(9) for z in range(9)})
    write_sponge_v3(os.path.join(out, "test_mv_slab.schem"), (5, 1, 5), {})
    write_sponge_v3(os.path.join(out, "test_mv_snow.schem"), (1, 1, 1), {(0, 0, 0): state("powder_snow")})
    write_sponge_v3(os.path.join(out, "test_mv_web.schem"), (1, 1, 1), {(0, 0, 0): state("cobweb")})
    write_sponge_v3(os.path.join(out, "test_big_dig.schem"), (200, 10, 200), {})
    if "run-tfc" in out.replace("\\", "/"):
        write_sponge_v3(os.path.join(out, "test_tfc_shelf.schem"), (3, 3, 3), tfc_shelf())
        write_sponge_v3(os.path.join(out, "test_tfc_made.schem"), (7, 2, 3),
                        made("tfc:grass_path/fluvisol", "tfc:farmland/fluvisol", "tfc:quern"))


targets = sys.argv[1:] or [os.path.join(REPO, d, "schematics") for d in ("neoforge/run", "neoforge/run-tfc", "fabric/runs/server")
                           if os.path.isdir(os.path.join(REPO, d))]
for target in targets:
    write_all(target)
    print("Wrote test schematics to " + target)
