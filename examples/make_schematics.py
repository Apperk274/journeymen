import gzip
import os
import struct
import sys

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_BYTE_ARRAY = 0, 1, 2, 3, 8, 9, 10, 11, 7
DATA_VERSION = 3955


class Byte(int):
    pass


class Short(int):
    pass


class IntArray(list):
    pass


class ByteArray(bytes):
    pass


def tag_type(value):
    if isinstance(value, dict):
        return TAG_COMPOUND
    if isinstance(value, IntArray):
        return TAG_INT_ARRAY
    if isinstance(value, ByteArray):
        return TAG_BYTE_ARRAY
    if isinstance(value, list):
        return TAG_LIST
    if isinstance(value, str):
        return TAG_STRING
    if isinstance(value, Byte):
        return TAG_BYTE
    if isinstance(value, Short):
        return TAG_SHORT
    if isinstance(value, int):
        return TAG_INT
    raise TypeError(value)


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def payload(value):
    kind = tag_type(value)
    if kind == TAG_COMPOUND:
        return b"".join(bytes([tag_type(v)]) + string(k) + payload(v) for k, v in value.items()) + bytes([TAG_END])
    if kind == TAG_INT_ARRAY:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i", v) for v in value)
    if kind == TAG_BYTE_ARRAY:
        return struct.pack(">i", len(value)) + bytes(value)
    if kind == TAG_LIST:
        element = tag_type(value[0]) if value else TAG_END
        return bytes([element]) + struct.pack(">i", len(value)) + b"".join(payload(v) for v in value)
    if kind == TAG_STRING:
        return string(value)
    if kind == TAG_BYTE:
        return struct.pack(">b", value)
    if kind == TAG_SHORT:
        return struct.pack(">h", value)
    return struct.pack(">i", value)


def write(path, root, root_name=""):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as file:
        file.write(bytes([TAG_COMPOUND]) + string(root_name) + payload(root))


def state(name, **properties):
    if not properties:
        return name if ":" in name else "minecraft:" + name
    props = ",".join(f"{key}={str(value).lower()}" for key, value in properties.items())
    return f"minecraft:{name}[{props}]"


def parse_state(text):
    if "[" not in text:
        return {"Name": text}
    name, props = text[:-1].split("[", 1)
    return {"Name": name, "Properties": dict(pair.split("=", 1) for pair in props.split(","))}


def write_structure(path, size, blocks, block_entities=None, data_version=DATA_VERSION):
    block_entities = block_entities or {}
    palette, index, entries = [], {}, []
    for pos in sorted(blocks):
        text = blocks[pos]
        if text not in index:
            index[text] = len(palette)
            palette.append(parse_state(text))
        entry = {"pos": list(pos), "state": index[text]}
        if pos in block_entities:
            entry["nbt"] = block_entities[pos]
        entries.append(entry)
    write(path, {"DataVersion": data_version, "size": list(size), "palette": palette, "blocks": entries, "entities": []})


def varints(values):
    out = bytearray()
    for value in values:
        while True:
            low = value & 0x7F
            value >>= 7
            out.append(low | 0x80 if value else low)
            if not value:
                break
    return ByteArray(bytes(out))


def sponge_data(size, blocks):
    width, height, length = size
    palette, data = {}, []
    for y in range(height):
        for z in range(length):
            for x in range(width):
                text = blocks.get((x, y, z), "minecraft:air")
                data.append(palette.setdefault(text, len(palette)))
    return palette, varints(data)


def write_sponge_v3(path, size, blocks, offset=(0, 0, 0), block_entities=None):
    palette, data = sponge_data(size, blocks)
    entities = [{"Pos": IntArray(list(pos)), "Id": tag["id"], "Data": {k: v for k, v in tag.items() if k != "id"}}
                for pos, tag in (block_entities or {}).items()]
    write(path, {"Schematic": {
        "Version": 3, "DataVersion": DATA_VERSION,
        "Width": Short(size[0]), "Height": Short(size[1]), "Length": Short(size[2]),
        "Offset": IntArray(list(offset)),
        "Blocks": {"Palette": palette, "Data": data, "BlockEntities": entities},
    }})


def write_sponge_v2(path, size, blocks, data_version, we_offset=(0, 0, 0)):
    palette, data = sponge_data(size, blocks)
    write(path, {
        "Version": 2, "DataVersion": data_version,
        "Width": Short(size[0]), "Height": Short(size[1]), "Length": Short(size[2]),
        "PaletteMax": len(palette), "Palette": palette, "BlockData": data, "BlockEntities": [],
        "Metadata": {"WEOffsetX": we_offset[0], "WEOffsetY": we_offset[1], "WEOffsetZ": we_offset[2]},
    }, "Schematic")


def sign_text(lines, color="black", glowing=False):
    messages = [f'"{line}"' for line in lines] + ['""'] * (4 - len(lines))
    return {"messages": messages, "color": color, "has_glowing_text": Byte(1 if glowing else 0)}


def wheat_farm():
    size = (11, 3, 11)
    blocks = {}
    for x in range(11):
        for z in range(11):
            border = x in (0, 10) or z in (0, 10)
            blocks[(x, 0, z)] = state("coarse_dirt") if border else state("farmland", moisture=7)
            if border:
                blocks[(x, 1, z)] = state("oak_fence")
    blocks[(5, 0, 5)] = state("water", level=0)
    blocks[(5, 1, 0)] = state("oak_fence_gate", facing="south", open=False, powered=False, in_wall=False)
    blocks[(5, 0, 0)] = state("dirt_path")
    for x, z in ((0, 0), (10, 0), (0, 10), (10, 10)):
        blocks[(x, 2, z)] = state("torch")
    crops = {1: "wheat", 2: "wheat", 3: "wheat", 4: "carrots", 5: "wheat", 6: "potatoes", 7: "beetroots", 8: "beetroots", 9: "wheat"}
    for z, crop in crops.items():
        for x in range(1, 10):
            if (x, z) == (5, 5):
                continue
            stages = 4 if crop == "beetroots" else 8
            blocks[(x, 1, z)] = state(crop, age=(x + z) % stages)
    write_structure(os.path.join(OUT, "wheat_farm.nbt"), size, blocks)


def cottage():
    size = (7, 6, 7)
    blocks = {}
    for x in range(7):
        for z in range(7):
            blocks[(x, 0, z)] = state("stone_bricks")
            blocks[(x, 4, z)] = state("oak_planks")
            edge = x in (0, 6) or z in (0, 6)
            for y in (1, 2, 3):
                if edge:
                    corner = x in (0, 6) and z in (0, 6)
                    blocks[(x, y, z)] = state("oak_log", axis="y") if corner else state("oak_planks")
            if z == 0 and 0 < x < 6:
                blocks[(x, 5, z)] = state("oak_stairs", facing="south", half="bottom", shape="straight", waterlogged=False)
            elif z == 6 and 0 < x < 6:
                blocks[(x, 5, z)] = state("oak_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
            elif x == 0:
                blocks[(x, 5, z)] = state("oak_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
            elif x == 6:
                blocks[(x, 5, z)] = state("oak_stairs", facing="west", half="bottom", shape="straight", waterlogged=False)
            else:
                blocks[(x, 5, z)] = state("oak_slab", type="bottom", waterlogged=False)
    blocks[(3, 1, 0)] = state("oak_door", half="lower", facing="south", hinge="left", open=False, powered=False)
    blocks[(3, 2, 0)] = state("oak_door", half="upper", facing="south", hinge="left", open=False, powered=False)
    for pos in ((1, 2, 0), (5, 2, 0), (3, 2, 6)):
        blocks[pos] = state("glass_pane", east=True, west=True, north=False, south=False, waterlogged=False)
    for pos in ((0, 2, 3), (6, 2, 3)):
        blocks[pos] = state("glass_pane", north=True, south=True, east=False, west=False, waterlogged=False)
    blocks[(1, 1, 5)] = state("red_bed", part="foot", facing="north", occupied=False)
    blocks[(1, 1, 4)] = state("red_bed", part="head", facing="north", occupied=False)
    blocks[(5, 1, 5)] = state("crafting_table")
    blocks[(4, 1, 5)] = state("furnace", facing="north", lit=False)
    blocks[(3, 1, 5)] = state("chest", facing="north", type="single", waterlogged=False)
    blocks[(5, 1, 3)] = state("bookshelf")
    blocks[(5, 1, 1)] = state("potted_red_tulip")
    for pos in ((2, 1, 2), (3, 1, 2), (2, 1, 3), (3, 1, 3)):
        blocks[pos] = state("white_carpet")
    blocks[(3, 3, 3)] = state("lantern", hanging=True, waterlogged=False)
    blocks[(1, 2, 1)] = state("wall_torch", facing="east")
    blocks[(4, 2, 1)] = state("oak_wall_sign", facing="south", waterlogged=False)
    entities = {(4, 2, 1): {"id": "minecraft:sign", "is_waxed": Byte(0),
                            "front_text": sign_text(["Welcome", "home"], color="blue"), "back_text": sign_text([])}}
    write_sponge_v3(os.path.join(OUT, "cottage.schem"), size, blocks, offset=(-3, 0, 0), block_entities=entities)


def decor_corner():
    size = (5, 3, 3)
    blocks = {}
    for x in range(5):
        for z in range(3):
            blocks[(x, 0, z)] = state("stone")
        blocks[(x, 1, 2)] = state("stone")
        blocks[(x, 2, 2)] = state("stone")
    blocks[(0, 1, 1)] = state("lectern", facing="north", has_book=True, powered=False)
    blocks[(1, 1, 1)] = state("jukebox", has_record=True)
    blocks[(2, 1, 1)] = state("chiseled_bookshelf", facing="north", slot_0_occupied=True, slot_1_occupied=False,
                              slot_2_occupied=True, slot_3_occupied=False, slot_4_occupied=False, slot_5_occupied=False)
    blocks[(3, 1, 1)] = state("decorated_pot", facing="north", cracked=False, waterlogged=False)
    blocks[(4, 1, 1)] = state("white_banner", rotation=8)
    blocks[(1, 2, 1)] = state("oak_wall_sign", facing="north", waterlogged=False)
    book = {"id": "minecraft:writable_book", "count": 1,
            "components": {"minecraft:writable_book_content": {"pages": [{"raw": "Built by a journeyman."}]}}}
    entities = {
        (0, 1, 1): {"id": "minecraft:lectern", "Book": book, "Page": 0},
        (1, 1, 1): {"id": "minecraft:jukebox", "RecordItem": {"id": "minecraft:music_disc_cat", "count": 1}},
        (2, 1, 1): {"id": "minecraft:chiseled_bookshelf", "last_interacted_slot": 2, "Items": [
            {"Slot": Byte(0), "id": "minecraft:book", "count": 1},
            {"Slot": Byte(2), "id": "minecraft:enchanted_book", "count": 1,
             "components": {"minecraft:stored_enchantments": {"levels": {"minecraft:mending": 1}}}}]},
        (3, 1, 1): {"id": "minecraft:decorated_pot",
                    "sherds": ["minecraft:brick", "minecraft:arms_up_pottery_sherd", "minecraft:brick", "minecraft:heart_pottery_sherd"],
                    "item": {"id": "minecraft:diamond", "count": 1}},
        (4, 1, 1): {"id": "minecraft:banner", "patterns": [{"pattern": "minecraft:stripe_top", "color": "red"},
                                                             {"pattern": "minecraft:circle", "color": "blue"}]},
        (1, 2, 1): {"id": "minecraft:sign", "is_waxed": Byte(0),
                    "front_text": sign_text(["Decor corner"], color="lime", glowing=True), "back_text": sign_text([])},
    }
    write_structure(os.path.join(OUT, "decor_corner.nbt"), size, blocks, entities)


def redstone_demo():
    size = (6, 2, 1)
    blocks = {(x, 0, 0): state("stone") for x in range(6)}
    blocks[(0, 1, 0)] = state("lever", face="floor", facing="east", powered=False)
    blocks[(1, 1, 0)] = state("redstone_wire", east="side", west="side", north="none", south="none", power=0)
    blocks[(2, 1, 0)] = state("repeater", facing="west", delay=2, locked=False, powered=False)
    blocks[(3, 1, 0)] = state("redstone_lamp", lit=False)
    blocks[(4, 1, 0)] = state("redstone_wire", east="side", west="side", north="none", south="none", power=0)
    blocks[(5, 1, 0)] = state("sticky_piston", facing="up", extended=False)
    write_structure(os.path.join(OUT, "redstone_demo.nbt"), size, blocks)


def clear_area():
    write_sponge_v3(os.path.join(OUT, "clear_area.schem"), (9, 8, 9), {})


def tests():
    old = {}
    for x in range(3):
        for z in range(3):
            old[(x, 0, z)] = state("stone")
    old[(1, 0, 1)] = state("grass_block", snowy=False)
    old[(1, 1, 1)] = "minecraft:grass"
    old[(0, 1, 0)] = state("oak_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
    old[(2, 1, 2)] = "othermod:widget"
    write_sponge_v2(os.path.join(OUT, "test_old_version_and_missing_mod.schem"), (3, 2, 3), old, data_version=3465, we_offset=(-1, 0, -1))
    with open(os.path.join(OUT, "test_broken.schem"), "wb") as file:
        file.write(b"this is not nbt")
    portal = {(x, y, 0): state("obsidian") for x in range(4) for y in (0, 4)}
    for y in range(1, 4):
        portal[(0, y, 0)] = state("obsidian")
        portal[(3, y, 0)] = state("obsidian")
        portal[(1, y, 0)] = state("nether_portal", axis="x")
        portal[(2, y, 0)] = state("nether_portal", axis="x")
    write_structure(os.path.join(OUT, "test_nether_portal.nbt"), (4, 5, 1), portal)


def tfc_farm():
    size = (9, 2, 9)
    blocks = {}
    for x in range(9):
        for z in range(9):
            border = x in (0, 8) or z in (0, 8)
            blocks[(x, 0, z)] = "tfc:grass/fluvisol[snowy=false]" if border else "tfc:farmland/fluvisol"
            if not border and (x, z) != (4, 4):
                blocks[(x, 1, z)] = f"tfc:crop/wheat[age={(x + z) % 8}]" if z < 4 else f"tfc:crop/carrot[age={(x + z) % 5}]"
    blocks[(4, 0, 0)] = "tfc:grass_path/fluvisol"
    blocks[(4, 0, 4)] = state("water", level=0)
    blocks[(0, 1, 0)] = "tfc:metal/chain/copper[axis=y]"
    blocks[(8, 1, 8)] = "tfc:pumpkin"
    write_sponge_v3(os.path.join(OUT, "tfc_farm.schem"), size, blocks)


OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "schematics")
wheat_farm()
cottage()
decor_corner()
redstone_demo()
clear_area()
tfc_farm()
tests()
print("Wrote example schematics to " + OUT)
