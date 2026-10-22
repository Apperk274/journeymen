# Journeymen

Minecraft **1.21.1** mod for **NeoForge and Fabric**, built from MultiLoader-Template. Mod ID `journeymen`, package `io.github.apperk274.journeymen`. The owner plays a 1.21.1 modpack and wants the mod to work in it, so we stay on 1.21.1.

## Commands

Always run Gradle through mise. There is no global `JAVA_HOME`, and Java 21 comes from `mise.toml`.

```sh
mise exec -- ./gradlew build
mise exec -- ./gradlew :neoforge:runClient
mise exec -- ./gradlew :fabric:runClient
mise exec -- ./gradlew :neoforge:runData
```

A dev client opens a game window. To verify a change without a person watching, launch it in the background, wait for `Sound engine started` (or a crash) in the output, then stop it and kill the leftover `java.exe`.

## Architecture rules

- Put as much as possible in `common/`. It sees only vanilla Minecraft (Mojang mappings + Parchment), so no NeoForge or Fabric imports.
- Loader-specific code goes in `neoforge/` or `fabric/`: registration, entity renderer registration, commands registration, networking, menu types, config. Expose it to common through an interface in `common/.../platform/services` with a `META-INF/services` entry in each loader.
- Every feature has to work on both loaders. Test each change on both clients.
- `neoforge_version` in `gradle.properties` is also the minimum NeoForge version the mod declares. It must not be newer than the NeoForge version in the owner's modpack (21.1.248). Likewise `fabric_version` (Fabric API, 0.116.7) is the declared minimum in `fabric.mod.json`, and Fabric loader is pinned low (0.16.9). Don't bump these without a reason.
- A new `gradle.properties` key that resources need must be added to `expandProps` in `buildSrc/src/main/groovy/multiloader-common.gradle`.

## What the mod is

The player summons a **builder** (a journeyman). The builder takes a schematic and builds it in the world **by hand**, like a survival player. Nothing appears out of thin air, and it is not creative mode.

"Journeymen" was chosen so more trades (farmer, miner, …) can be added later as other journeyman types. Builder is the first.

### Builder entity

- A custom `PathfinderMob` (not a villager) with its own AI goals.
- Rendered with the vanilla player model and default Steve skin (`textures/entity/player/wide/steve.png`) for now.
- Has an owner (the player who summoned it). Only the owner commands it.
- Has an inventory. Right-click opens it, and the player moves items in and out. Starting with vanilla's 9×3 chest menu is fine; a custom screen with tool slots can come later.
- Inventory, owner and current build job persist through world save/load.
- Summoned with a command first (`/builder summon`). A craftable spawn egg comes later.

### Schematics

- Formats for the first version: **Sponge `.schem`** (WorldEdit, v2 and v3) and **vanilla structure `.nbt`**. `.litematic` comes later.
- Files are read from a `schematics/` folder in the game directory (server side).
- `/builder build <file> <x y z> [rotation]` assigns a job to the builder. In-world placement preview and area-based commands are later features.

### Realistic building (core requirement)

1. **Plan:** diff the schematic against the world. Build a list of blocks to remove and blocks to place. Place bottom-up, with attached/gravity/fragile blocks (torches, doors, redstone, sand, carpets) after their supports.
2. **Clear:** walk within reach, look at the block, swing, show crack progress (`destroyBlockProgress`). Break time follows player rules for the held tool. Use tool durability. Drops go to the builder's inventory. Blocks that need a tool tier can't be harvested without it.
3. **Place:** consume the matching item from inventory per block, with swing and sound. Keep the block state (facing, half, axis…). Map special cases (redstone wire → redstone, double slab → 2 slabs, water → water bucket, two-block doors/beds).
4. **Missing material:** pause and tell the owner exactly what's missing, then resume when it's supplied.
5. **Reach:** vanilla pathfinding can't climb. Start with what the builder can reach from the ground and from the structure itself. Scaffolding/pillaring comes in a later phase.

## Roadmap

| Phase | Done when |
|---|---|
| 0 ✅ | Empty mod builds and launches on NeoForge and Fabric |
| 1 | Summon a Steve-skinned builder by command, open its inventory, and it survives reload |
| 2 | Load `.schem` / `.nbt` and print a material list for a schematic |
| 3 | Builder builds: walks, breaks with tools, places from inventory, waits when out of materials |
| 4 | Scaffolding for tall builds, craftable spawn egg, better feedback |

## Related mods (stay distinct from these)

- **Buildlings** (NeoForge 1.21.1): builder NPCs that pull materials from chests.
- **MineColonies**: builder citizens as part of a full colony system.
