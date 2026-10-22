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

To test commands and behaviour without a person, run a dev server (`:neoforge:runServer` or `:fabric:runServer`; the run dir needs `eula.txt`) with RCON enabled in its `server.properties`, and send commands over RCON. Turn RCON off again afterwards. Things to know:

- Gradle doesn't pass console input through to the server, so RCON is the way in.
- In the dev environment, `/journeymen debug as <player> <permission> <pos> <command>` runs a command as a fake player (for commands that need a player, like `builder summon`) and copies its output to the server log.
- RCON only returns output produced while the command runs. Output that arrives later (schematic loading, pre-check results) only shows up in the server log via `debug as`.
- A server with no players stops ticking entities 15 seconds after the last player leaves unless chunks are force-loaded, so `forceload add` the test area first. Force-loads may not survive a restart in the dev setup; add them again after restarting.

## Architecture rules

- Put as much as possible in `common/`. It sees only vanilla Minecraft (Mojang mappings + Parchment), so no NeoForge or Fabric imports.
- Loader-specific code goes in `neoforge/` or `fabric/`: registration, entity renderer registration, commands registration, networking, menu types, config. Expose it to common through an interface in `common/.../platform/services` with a `META-INF/services` entry in each loader.
- Every feature has to work on both loaders. Test each change on both clients.
- `neoforge_version` in `gradle.properties` is also the minimum NeoForge version the mod declares. It must not be newer than the NeoForge version in the owner's modpack (21.1.248). Likewise `fabric_version` (Fabric API, 0.116.7) is the declared minimum in `fabric.mod.json`, and Fabric loader is pinned low (0.16.9). Don't bump these without a reason.
- A new `gradle.properties` key that resources need must be added to `expandProps` in `buildSrc/src/main/groovy/multiloader-common.gradle`.
- Everything the player can do in the mod must also be possible with a command, so every feature can be tested and debugged without its UI. When a friendlier way is added (spawn egg, right-click dialogue, GUI button), the command stays. Examples: builders can always be summoned with `/journeymen builder summon`, even once a spawn egg exists. A build can always be started with `/journeymen builder build ...`, even if it can also be started from a right-click dialogue.

## What the mod is

The player summons a **builder** (a journeyman). The builder takes a schematic and builds it in the world **by hand**, like a survival player. Nothing appears out of thin air, and it is not creative mode.

"Journeymen" was chosen so more trades (farmer, miner, …) can be added later as other journeyman types. Builder is the first.

All commands live under `/journeymen`, with each trade as a subcommand (`/journeymen builder …`). `/journeymen help` lists them. There is no `/jm` alias, because JourneyMap already owns `/jm`.

### Builder entity

- A custom `PathfinderMob` (not a villager) with its own AI goals.
- Rendered with the vanilla player model and default Steve skin (`textures/entity/player/wide/steve.png`) for now.
- Has an owner (the player who summoned it). Only the owner commands it. Operators (permission level 2+) can command any builder, addressing it as `<owner>.<name>` (e.g. `Steve.Gerald`).
- Has a name. Summon gives it a random first name that is unique among the owner's builders, or the one given in `summon [name]`. A name tag or `/journeymen builder rename <old> <new>` renames it. Commands address builders by name, and the name shows above its head. Names use only letters, digits, `_` and `-` (at most 24), so they never need quotes in commands, and player names never contain the `.` separator.
- Has a 27-slot inventory. Right-click opens it, and the player moves items in and out. Vanilla's 9×3 chest menu for now; a custom screen with tool slots can come later.
- Inventory, owner, name, assigned chests and current build job persist through world save/load.
- Summoned with `/journeymen builder summon`. A craftable spawn egg comes later.
- Can be hurt like a mob (falls, lava, mobs, other players under PvP rules), including by its owner, and regenerates slowly. On death it drops its inventory. `/journeymen builder kill <name>` removes it and drops its inventory.
- Picks up items lying around itself (player pickup range), whether working or idle. It doesn't go out of its way for items.
- When idle it stays where it is. `builder come <name>` walks it to you, and `builder follow <name>` / `builder stay <name>` toggle following.

### Schematics

- Formats for the first version: **Sponge `.schem`** (WorldEdit, v2 and v3) and **vanilla structure `.nbt`**. `.litematic` comes later.
- Files are read from a `schematics/` folder in the game directory (server side). `/journeymen schematic list` lists them and `/journeymen schematic materials <file>` prints the material list.

### Build jobs

- `/journeymen builder build <name> <file> [<x y z>] [rotation] [options]` assigns a job. `/journeymen builder check` with the same arguments is a dry run that prints the pre-check without starting. The arguments follow vanilla `/place template`: the position defaults to where the player stands, and rotation uses the vanilla names (`none`, `clockwise_90`, `180`, `counterclockwise_90`).
- **Origin:** by default `<x y z>` is the WorldEdit paste origin (the offset stored in a `.schem`, like `//paste`). `.nbt` files have no offset, so the min corner is used. The `corner` option uses the min corner for any file.
- **Rotation:** none, 90, 180 or 270 degrees clockwise around the origin. Block states rotate with it. Mirroring is in the backlog.
- **Area:** the rotated schematic bounding box. Schematic air is cleared. Structure void and positions without data are left alone.
- **Options:** `corner` (see Origin) and `allow-breaking-enchanted` (see Tools).
- **One job per builder.** `build` is refused while it has a job. `builder cancel <name>` stops it and leaves the half-built structure as it is. Leftover items stay in its inventory and chests. Issuing the same job again continues from what is in the world, because the plan is a diff.
- **Getting there:** it walks to the site. `build` is refused if the site is farther than a configured distance or no path is found.
- **Chunks:** like any mob, it stops when its chunks unload and continues when they load again. A config option (off by default) lets a builder with an active job keep its chunks loaded.
- It keeps working while the owner is offline.
- A config limit caps the job volume (generous by default, e.g. 256×256×256).
- The areas of two active jobs may not overlap.
- While a builder has a job, `come` and `follow` are refused (cancel the job first).

### Pre-check (core requirement)

`build` and `check` run the same check. A job starts only when everything passes; otherwise the builder lists every problem at once.

- **Materials:** every item needed for placing must already be in its inventory or its supply chests. Drops it will get from clearing don't count. Only plain items count and are used: no custom name, enchantments or contents (a renamed chest or a filled shulker box is never placed).
- **Tools:** every block to clear that needs a tool tier needs such a tool, and the tools need enough durability for all the blocks they will break. Durability is counted conservatively (1 per block, Unbreaking ignored; see Backlog). If there are 10 obsidian and its diamond pickaxe has 9 durability left, the check fails.
- **Enchanted tools** are never used below 1 durability by default, so good gear is never lost; the check counts them as having 1 less. `allow-breaking-enchanted` lifts this. Plain tools can be used up.
- **Made blocks:** blocks without a survival item are made like a player would: farmland = dirt + hoe, dirt path = dirt + shovel (the tool use counts toward durability). Grass block, mycelium and podzol need the silk-touched item.
- **Unbreakable blocks** (destroy speed < 0: bedrock, barrier, end portal frame, reinforced deepslate, …) in the clearing area fail the check.
- **Protection:** it breaks and places through a fake player with the owner's identity, so claim and protection mods allow it exactly where the owner could build. Positions the owner may not change fail the check.
- Size and distance limits.

### Supply and dump chests

- `/journeymen builder chest <name> add <x y z> supply|dump`, plus `remove` and `list`. A builder can have several chests of each role, used in order. Both halves of a double chest count as one chest. A friendlier way to assign them (e.g. shift-right-click) can come later; the command stays.
- **Supply chests** are stock: it walks over and takes the materials and tools it needs. They count in the pre-check.
- **Dump chests** take drops when its inventory is full. It never dumps into a supply chest and never builds from a dump chest. With no dump chest, or all of them full, it pauses and asks.

### How a build plays out (core requirement)

1. **Plan:** diff the schematic against the world into a list of blocks to remove and a list to place.
2. **Clear** the whole area first, top-down. Then place.
3. **Reach:** it walks until the block is within horizontal reach (player reach, 4.5 blocks). Height doesn't matter: it reaches any height, so there is no scaffolding or pillaring. If it can't get within horizontal reach (lava pool, void), it works from where it is anyway.
4. **Breaking:** look at the block, swing, show crack progress (`destroyBlockProgress`). Break time follows player rules for the held tool. It picks the fastest tool it has for each block and uses durability like a player. Drops go straight into its inventory, never on the ground, so nothing can be stolen or burn. When its inventory is full it walks to a dump chest. It breaks through the owner's fake player with vanilla's survival breaking, so tool wear, two-block blocks (doors, beds) and container contents behave as for a player; everything that drops is captured, and whatever doesn't fit is held (and saved) until it reaches a dump chest. At a dump chest it keeps its tools and the materials the job still needs. For blocks that need a tool it uses the least versatile tool that works (an iron pickaxe for stone when a diamond one is needed for obsidian), and it only uses a tool on other blocks when that durability isn't reserved. It fetches useful tools from its supply chests when clearing starts, and again when it lacks one.
5. **Fluids in the way:** water or lava where the schematic wants a block or air is not handled. Fluids come up in the same top-down order as the blocks, and at the first one it pauses and tells the owner the position. It checks again every few seconds and continues by itself once the fluid is gone.
6. **Placing:** bottom-up, with attached, gravity and fragile blocks (torches, doors, redstone, sand, carpets) after their supports. It can place anywhere within reach: no supporting neighbor and no line of sight needed. Like a player, it places straight into replaceable blocks (short grass, snow layers, …) without breaking them first. Each block consumes its item from inventory, with a swing and a sound. The block state is kept (facing, half, axis…) and rotated. Special cases follow the material list (redstone wire → redstone, double slab → 2 slabs, potted plant → pot + plant, …). Doors and beds place both halves from one item.
7. **Growth stages:** crops, saplings and other aging blocks are placed young (age 0), like a player, and grow on their own. Leaves are placed persistent, like player-placed leaves, so they never decay. States that come from neighbors (fence, pane and wall connections, stair shape, redstone wire connections and power, `lit`, `powered`) are not compared in the diff; the game sets them as it would for a player.
8. **Block updates** happen as for a player, so a half-built contraption may fire.
9. **Fluids last:** water and lava sources and waterlogging go in after every solid block, with buckets. The empty buckets come back to its inventory.
10. **Block entity data:** it copies sign text (including dye color and glow when it has the dye or glow ink sac), banner patterns (needs the patterned banner), and decor contents from stock (flower pot plants, lectern books, decorated pot item, chiseled bookshelf books, jukebox disc). Container contents are not copied. Entities (item frames, paintings, armor stands, mobs) are ignored in phase 3.
11. **Wrong state:** if the right block is there in the wrong state and a right-click would fix it (door, trapdoor or gate open/closed, lever, repeater delay, note block pitch, comparator mode), it right-clicks. Otherwise it breaks and replaces the block.
12. **In the way:** if a player or mob stands where a block goes, it waits. After about 10 seconds it tells the owner, then reminds every few minutes. It never places a block inside itself.
13. **Missing items mid-build** (taken out, or the world changed): pause, tell the owner exactly what is missing, and continue as soon as it shows up in its inventory or supply chests.
14. **Tampering:** before finishing, it re-checks the area and fixes differences. Each position is fixed at most N times (config, default 3), so griefing one spot can't loop it forever. Positions over the limit are reported at the end.
15. **Speed:** player pace by default, with a config multiplier.

### Feedback

- `/journeymen builder status <name>`: phase (walking, clearing, building, paused), percent done, and what it is waiting for.
- A boss bar shows progress to the owner while near the builder.
- Pause messages go to the owner in chat at any distance, and are also written to the server log. If the owner is offline, they are delivered on login. While paused it reminds every few minutes.

### Config

A small config file read by `common/`, same format on both loaders, no library dependency and no in-game GUI for now. Keys include the speed multiplier, chunk loading (off), the redo limit (3), the max job volume, the max walking distance to a site, and the reminder interval.

## Roadmap

| Phase | Done when |
|---|---|
| 0 ✅ | Empty mod builds and launches on NeoForge and Fabric |
| 1 ✅ | Summon a Steve-skinned builder by command, open its inventory, and it survives reload |
| 2 ✅ | Load `.schem` / `.nbt` and print a material list for a schematic |
| 3 | Builder builds a schematic as described in Build jobs, Pre-check, Supply and dump chests, How a build plays out and Feedback |
| 4 | Craftable spawn egg, right-click dialogue for common actions, custom inventory screen |

Phase 3 is built in steps, each tested on both loaders:

| Step | Done when |
|---|---|
| 3a ✅ | Config file, builder names and renaming, `<owner>.<name>` targeting for ops, `come` / `follow` / `stay` / `kill` / `status`, item pickup, damage and regeneration, fake player on both loaders |
| 3b ✅ | `check` / `build` / `cancel` with origin, rotation and options, chest assignment, the full pre-check, and jobs that survive reload |
| 3c ✅ | Clearing: walking into reach, breaking with tools at player speed, drops into inventory, dump chests, pausing for fluids |
| 3d | Placing: order, states, special cases, growth stages, made blocks, block entity data, fluids last, wrong-state fixing, waiting for entities, tampering and redo |
| 3e | Boss bar, pause notifications with offline delivery and reminders |

Scaffolding and pillaring were dropped: the builder reaches any height (see Reach).

Not tested yet (no way to test them so far):

- Other players hurting a builder when PvP is off (the builder should take no damage).
- Spawn protection and claim/protection mods (FTB Chunks, Open Parties and Claims, …) blocking the pre-check and the builder's breaking and placing.

Backlog (no phase yet): mirroring schematics in the build command, counting Unbreaking in the pre-check durability estimate instead of ignoring it, placing entities from schematics (item frames, paintings, armor stands), more jobs per builder (a queue), several builders on one job.

## Related mods (stay distinct from these)

- **Buildlings** (NeoForge 1.21.1): builder NPCs that pull materials from chests.
- **MineColonies**: builder citizens as part of a full colony system.
