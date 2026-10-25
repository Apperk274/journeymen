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
- Screen buttons can be tested the same way: `debug action <builder entity> <action> <pos> <text>` (run through `debug as`) sends a builder screen action as that player (`follow`, `rename`, `link_supply`, `check`, `start`, …; `|` in the text becomes a line break, `-` means empty) and logs the builder's state, `debug use <pos>` is a right-click on a block for chest link mode, and `debug interact <entity>` is a right-click on an entity (on a builder it ends link mode); it logs whether link mode is still on and which builder screen tab opened (`none` for the fake player, which can't open screens).
- RCON only returns output produced while the command runs. Output that arrives later (schematic loading, pre-check results) only shows up in the server log via `debug as`.
- A server with no players stops ticking entities 15 seconds after the last player leaves unless chunks are force-loaded, so `forceload add` the test area first. Force-loads may not survive a restart in the dev setup; add them again after restarting.
- `/tick rate <n>` (vanilla, default 20) speeds the server up while watching a long build, e.g. `tick rate 100`. Set it back to 20 afterwards.
- To reproduce something from the owner's singleplayer save, copy the save into the run dir, point `level-name` in its `server.properties` at the copy, and set it back afterwards. Never run the original. The owner isn't online there, so `forceload add` around the builder first.
- `fill` refuses more than 32768 blocks; split bigger areas, or old test builds stay in the way.

The placement preview can be checked in a dev client without a person. With a `journeymen-preview-test.properties` file in the client's run dir (dev environment only), the client creates a flat creative world, summons a builder, starts placement mode for each schematic in turn at the crosshair, logs frame times (`[preview test]` lines: without the preview, with it, and with the crosshair moving) and saves a screenshot per rotation to `screenshots/<out>_<file>_<rotation>.png`, then quits. Keys: `schematic` (comma-separated files), `options`, `rotations` (comma-separated), `frames`, `pos`, `yaw` and `pitch` (where to stand; the crosshair reaches 64 blocks), `out`, `world` (default `jm-preview-test`) and `quit`. Delete the file and the world afterwards, or every dev client launch runs it again.

### Testing with TerraFirmaCraft

The owner's modpack is TerraFirma Rebirth 2 (NeoForge 21.1.248, TFC 4.2.9 and many TFC addons), so changes are also tested with TFC. NeoForge has two extra runs, `runTfcServer` and `runTfcClient`, that use `neoforge/run-tfc` (git-ignored) instead of `neoforge/run`. `mise exec -- ./gradlew :neoforge:syncTfcMods` copies the jars listed in `neoforge/tfc-test-mods.txt` (TFC, Patchouli, Firmalife, ArborFirmaCraft, FarmersTFC + Farmer's Delight, TFC Real World, FTB Chunks with its libraries, WorldEdit) from the CurseForge instance into `run-tfc/mods`; pass `-Ptfc_instance=<path>` if the instance isn't at the default CurseForge location. Production NeoForge jars run unchanged in the dev environment. The run dir needs `eula.txt`, a `schematics/` folder (the examples plus the owner's TFC schematics) and its own config. TFC is NeoForge-only, so Fabric only has to keep working without it.

Things TFC changes that matter for tests: it caps many stack sizes (dirt stacks to 32, so `item replace ... 64` fails), the pack unifies vanilla emeralds, buckets and metals into TFC items, TFC soils are `tfc:dirt/<soil>`, `tfc:grass/<soil>`, `tfc:farmland/<soil>` and `tfc:grass_path/<soil>` (soils such as `fluvisol`, `entisol`), and TFC storage (barrels, large vessels) has no vanilla container, only a NeoForge item handler. `examples/schematics/tfc_farm.schem` is the TFC end-to-end case (grass, farmland with crops, a path, water, a copper chain and a pumpkin).

## Architecture rules

- Put as much as possible in `common/`. It sees only vanilla Minecraft (Mojang mappings + Parchment), so no NeoForge or Fabric imports.
- Loader-specific code goes in `neoforge/` or `fabric/`: registration, entity renderer registration, commands registration, networking, menu types, config. Expose it to common through an interface in `common/.../platform/services` with a `META-INF/services` entry in each loader.
- Every feature has to work on both loaders. Test each change on both clients.
- Other mods are reached through loader APIs, never through their classes: storage through NeoForge's item handler capability and Fabric's Transfer API, fluid containers through NeoForge's `FluidUtil` and Fabric's fluid storage, hoes and shovels through NeoForge item abilities (`ICompatHelper`). Rules about blocks come from the game itself (loot tables, tool actions, block states), not from lists of vanilla blocks, so modded blocks follow them too.
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
- Has a 27-slot inventory and armor slots. It wears its armor (rendered), which protects it and wears out like a player's, and drops on death. Right-click opens the builder screen (see Phase 4).
- Inventory, owner, name, assigned chests and current build job persist through world save/load.
- Summoned with `/journeymen builder summon` (ops), or hired from a recruiter in survival (see Recruiter's Stall).
- Can be hurt like a mob (falls, lava, mobs, other players under PvP rules), including by its owner, and regenerates slowly. On death it drops its inventory. `/journeymen builder kill <name>` removes it and drops its inventory.
- Picks up items lying around itself (player pickup range), whether working or idle. It doesn't go out of its way for items.
- When idle it stays where it is. `builder come <name>` walks it to you, and `builder follow <name>` / `builder stay <name>` toggle following.
- **Getting unstuck:** like a tamed wolf, but going by time stuck rather than distance alone. While it is going somewhere (a chest it wants, the build site, the spot it picked to get within reach of the next blocks, the spot it was called to with `come`, or its owner when following and at least 12 blocks behind) and hasn't got 0.5 blocks closer to it, nor broken, placed or fetched anything, for `stuck_teleport_seconds` (20 by default, 0 turns it off), it teleports to a safe spot next to that goal: floor under it, room to stand, no fluid, fire or leaves, in an entity-ticking chunk of the same dimension, in sight of the goal (within chest reach of a chest, within reach of the site's edge, 2–3 blocks from its owner like a wolf), preferably outside the job area and never where the job will still place a block or clear the floor, and closer to the goal than it is. A pause (waiting for materials, a full dump chest, someone in the way) doesn't count as stuck time but doesn't reset it either, so retrying an unreachable chest adds up. It sounds and shows particles like a chorus fruit teleport, logs `Stuck for … teleported to …` on the server, and only tells its owner when it happened 3 times within 10 minutes (then at most once per reminder interval). After a teleport it waits at least 30 s before the next; when no safe spot exists it tries again every 10 s and the old fallbacks (pausing for an unreachable chest or site) still apply. A builder told to `stay` never teleports by itself. `/journeymen builder unstick <name>` does the same at once: towards its current goal, or the site (or a chest it is heading for) during a job, its owner when following, otherwise the player who ran the command. `builder status` shows how long it has been stuck and when, from where and to where it last got out (saved with the world). Working from where it is (see Reach) counts as progress, so it is not a reason to teleport.
- It walks through wooden doors and fence gates like a player: it opens them on its way and closes them behind it if they were closed, leaving open ones open (like villagers). Iron doors stay shut.

### Schematics

- Formats: **Sponge `.schem`** (WorldEdit, v2 and v3), **Litematica `.litematic`**, **vanilla structure `.nbt`** and old **MCEdit / WorldEdit `.schematic`** (pre-1.13, numeric block ids). Blocks and block entities saved by older Minecraft versions are upgraded with the game's own data fixers.
- `.litematic`: all regions are combined into one schematic over their overall bounding box. Positions no region covers have no data and are left alone, like structure void.
- `.schematic`: legacy id and metadata are flattened the way the game converted 1.12 worlds to 1.13 (`BlockStateData` and the chunk upgrade, which also takes bed and banner colors, flower pot plants, skulls, note block notes and door halves from block entities and neighbors), then upgraded to the current version. Block entities (signs, chests, …) are upgraded too. Ids above 255 (old modded blocks) and ids the game never had are reported as unknown blocks and left alone. A `.schematic` that is really a Sponge file (some tools write those) is read as one.
- Files are read from a `schematics/` folder in the game directory (server side), including subfolders, such as Create's server uploads in `schematics/uploaded/<player>/`. A schematic is named by its path relative to `schematics/` with `/` (e.g. `uploaded/Steve/house.nbt`); paths that leave the folder are refused. Brigadier needs quotes around names with `/` or spaces (`"uploaded/Steve/house.nbt"` or `'uploaded/Steve/house.nbt'`), and the file suggestions offer them quoted. `/journeymen schematic list` lists them and `/journeymen schematic materials <file>` prints the material list.

### Build jobs

- `/journeymen builder build <name> <file> [<x y z>] [rotation] [options]` assigns a job. `/journeymen builder check` with the same arguments is a dry run that prints the pre-check without starting. The arguments follow vanilla `/place template`: the position defaults to where the player stands, and rotation uses the vanilla names (`none`, `clockwise_90`, `180`, `counterclockwise_90`).
- **Origin:** by default `<x y z>` is the paste origin the file stores: the WorldEdit offset in a `.schem` or `.schematic` (`WEOffsetX/Y/Z`, like `//paste`), and the litematic's own origin in a `.litematic` (region coordinates 0,0,0, where Litematica puts the placement origin). `.nbt` files and `.schematic` files without a WorldEdit offset have none, so the min corner is used. The `corner` option uses the min corner for any file.
- **Rotation:** none, 90, 180 or 270 degrees clockwise around the origin. Block states rotate with it. Mirroring is in the backlog.
- **Area:** the rotated schematic bounding box. Schematic air is cleared. Structure void and positions without data are left alone.
- **Options:** `corner` (see Origin), `allow-breaking-enchanted` (see Tools), `skip-unplaceable` and `skip-missing` (see Pre-check).
- **One job per builder.** `build` is refused while it has a job. `builder cancel <name>` stops it and leaves the half-built structure as it is. Leftover items stay in its inventory and chests. Issuing the same job again continues from what is in the world, because the plan is a diff.
- **Getting there:** before walking to the site it fetches what the first stage needs from its supply chests (tools if there is anything to clear, otherwise the materials for the first blocks plus any hoe or shovel for farmland and paths), then walks to the site. `build` is refused if the site is farther than a configured distance or no path is found.
- **Chunks:** like any mob, it stops when its chunks unload and continues when they load again. A config option (off by default) lets a builder with an active job keep its chunks loaded.
- It keeps working while the owner is offline.
- A config limit caps the job volume (generous by default, e.g. 256×256×256).
- The areas of two active jobs may not overlap.
- While a builder has a job, `come` and `follow` are refused (cancel the job first).

### Pre-check (core requirement)

`build` and `check` run the same check. A job starts only when everything passes; otherwise the builder lists every problem at once.

- **Materials:** every item needed for placing must already be in its inventory or its supply chests. Drops it will get from clearing don't count. Only plain items count and are used: no custom name, enchantments, contents or other vanilla data (a renamed chest or a filled shulker box is never placed). Data that other mods add is ignored when matching, so TFC metal items (which always carry `tfc:heat`) and TFC food blocks such as pumpkins (which carry a creation date) count.
- **Tools:** every block to clear that needs a tool tier needs such a tool, and the tools need enough durability for all the blocks they will break. Durability is counted conservatively (1 per block, Unbreaking ignored; see Backlog). If there are 10 obsidian and its diamond pickaxe has 9 durability left, the check fails.
- **Enchanted tools** are never used below 1 durability by default, so good gear is never lost; the check counts them as having 1 less. `allow-breaking-enchanted` lifts this. Plain tools can be used up.
- **Made blocks:** a block that drops another block which a hoe or a shovel turns into it is made like a player would: farmland = dirt + hoe, dirt path = dirt + shovel, and the same for modded soils (TFC farmland and grass paths). The tool use counts toward durability, and any tool that can till or flatten works (TFC hoes and shovels too).
- **Grass:** blocks with a `snowy` state (grass block, mycelium, podzol, TFC grass) are placed as the block they drop without Silk Touch (dirt, TFC dirt) and count as done once that is there; the grass spreads by itself, like crops grow. Blocks that drop no block (TFC clay grass drops clay) still need their item.
- **Can't be placed by hand:** blocks that have no item at all (TFC's forge, which is made by lighting a charcoal pile; fire; portals) fail the check. Blocks that have an item that survival can't get (TFC clay grass, bedrock) show up as missing materials instead.
- **Skipping:** `skip-unplaceable` leaves the blocks that can't be placed by hand alone, like structure void (not cleared, not placed). `skip-missing` lets the job start with materials missing; during the build, a block whose items are neither in its inventory nor in its supply chests is skipped instead of pausing. Each problem needs its own option, and the check names the option when that problem is present. Skipped blocks are reported when the job ends and aren't retried by the tampering pass. Tools are always required.
- **Unbreakable blocks** (destroy speed < 0: bedrock, barrier, end portal frame, reinforced deepslate, …) in the clearing area fail the check.
- **Protection:** it breaks and places through a fake player with the owner's identity, so claim and protection mods allow it exactly where the owner could build. Positions the owner may not change fail the check.
- Size and distance limits.

### Supply and dump chests

- `/journeymen builder chest <name> add <x y z> supply|dump`, plus `remove` and `list`. A builder can have several chests of each role, used in order. Both halves of a double chest count as one chest. Any block with an item inventory works as a "chest": vanilla containers, and modded storage that only offers the loader's item API (TFC barrels and large vessels, drawers). In survival they are assigned with link mode from the builder screen (see Builder screen); the command stays.
- **Supply chests** are stock: it walks over and takes the materials and tools it needs. They count in the pre-check.
- **Dump chests** take drops when its inventory is full, and whatever it doesn't need once the site is clear.
- `/journeymen builder supply <builder> [<file> [pos] [rotation] [options]]` (ops only, creates items) puts the full material list of a job into the builder's supply chests, plus the cheapest plain tools that cover the job's required tool uses (e.g. enough wooden pickaxes for the stone, a diamond pickaxe for obsidian, a hoe for farmland). Without a file it uses the builder's current job. What doesn't fit in the supply chests goes to the player's inventory. It is all or nothing: it works out where every item goes first, and if anything doesn't fit it gives nothing and says how much didn't fit. It never dumps into a supply chest and never builds from a dump chest. With no dump chest, or all of them full, it pauses and asks.

### How a build plays out (core requirement)

1. **Plan:** diff the schematic against the world into a list of blocks to remove and a list to place.
2. **Clear** the whole area first, top-down. Then place.
3. **Reach:** it walks until the block is within horizontal reach (player reach, 4.5 blocks). Height doesn't matter: it reaches any height, so there is no scaffolding or pillaring. Of the blocks that are next in order, it takes one within reach if there is one, otherwise the nearest one it can walk to within reach (a path search over the site), so it never waits on a block it can't get to while others are reachable. Only when it can't get within horizontal reach of any of them (lava pool, void) does it work from where it is anyway, without waiting per block. The path search follows the same movement rules as its walking: a block that isn't full but sticks up into the space above its floor (a TFC mold table or channel, a carpet, a flower pot) raises the floor if it is low enough to step onto, so climbing onto a block with one on top is too high a jump, taller ones are jumped onto like full blocks, and it never plans to stand where its head would hit something. If it still stops getting closer to the spot it picked, it gets itself out as described in Getting unstuck; with that off, or when there is no safe spot, it gives up on that spot, works from where it is and doesn't search again from the same place for a while (5 s, doubling up to 30 s).
4. **Breaking:** look at the block, swing, show crack progress (`destroyBlockProgress`). Break time follows player rules for the held tool. It picks the fastest tool it has for each block and uses durability like a player. Drops go straight into its inventory, never on the ground, so nothing can be stolen or burn. When its inventory is full it walks to a dump chest; if everything it carries is still needed for the job, it puts it into a supply chest instead. It breaks through the owner's fake player with vanilla's survival breaking, so tool wear, two-block blocks (doors, beds) and container contents behave as for a player; everything that drops is captured, and whatever doesn't fit is held (and saved) until it reaches a dump chest. At a dump chest it keeps its tools and the materials the job still needs. When the site is clear it also takes everything else it collected to its dump chests, even if its inventory isn't full; if they are full it says so and keeps the rest, and without a dump chest it keeps everything. For blocks that need a tool it uses the least versatile tool that works (an iron pickaxe for stone when a diamond one is needed for obsidian), and it only uses a tool on other blocks when that durability isn't reserved. It fetches useful tools from its supply chests when clearing starts, and again when it lacks one.
5. **Fluids in the way:** water or lava where the schematic wants a block or air is not handled. Fluids come up in the same top-down order as the blocks, and at the first one it pauses and tells the owner the position. It checks again every few seconds and continues by itself once the fluid is gone.
6. **Placing:** bottom-up, with attached, gravity and fragile blocks (torches, doors, redstone, sand, carpets) after their supports. It can place anywhere within reach: no supporting neighbor and no line of sight needed. Like a player, it places straight into replaceable blocks (short grass, snow layers, …) without breaking them first. Each block consumes its item from inventory, with a swing and a sound. The block state is kept (facing, half, axis…) and rotated. Special cases follow the material list (redstone wire → redstone, double slab → 2 slabs, potted plant → pot + plant, …). Doors and beds place both halves from one item. Blocks that hang on something (torches, doors, rails, redstone, carpets, plants, vines, lanterns, …) come after all other blocks, each once its support can hold it. It fetches the materials for the next blocks from its supply chests, as much as fits. When the next blocks need more room than it has free, it first unloads in one trip: junk to a dump chest, and job materials it won't need soon back into a supply chest (they stay stock), then fills its inventory with what comes next. If the supply chest is full it stops trying that for a minute and carries what fits. Like a player it may walk on the structure as it rises; it can drop up to 6 blocks (taking fall damage) to get back down when healthy. Like a player who doesn't pillar, it leaves itself a way back up: along the edge of the site nearest its first supply chest, it keeps a one-block-wide staircase out of the schematic's own blocks (each step one higher than the last, with the blocks above the steps left out), so it can walk back onto a raised structure after a trip to its chests. It fills that notch after all other non-hanging blocks, from the top step down, and before the blocks that hang on something.
7. **Growth stages:** crops, saplings and other aging blocks are placed young (age 0), like a player, and grow on their own. Right after tilling farmland it plants the crop that goes on top, like a player, so the farmland doesn't dry back to dirt. Leaves are placed persistent, like player-placed leaves, so they never decay. States that come from neighbors (fence, pane and wall connections, stair shape, redstone wire connections and power, `lit`, `powered`) are not compared in the diff; the game sets them as it would for a player.
8. **Block updates** happen as for a player, so a half-built contraption may fire.
9. **Fluids last:** water and lava sources and waterlogging go in after every solid block, with any item that holds a full bucket of that fluid (vanilla buckets, TFC wooden and metal buckets, other mods' buckets). The emptied container comes back to its inventory.
10. **Block entity data:** it copies sign text (including dye color and glow when it has the dye or glow ink sac), banner patterns (needs the patterned banner), and decor contents from stock (flower pot plants, lectern books, decorated pot item, chiseled bookshelf books, jukebox disc). Container contents are not copied. Entities (item frames, paintings, armor stands, mobs) are ignored in phase 3.
11. **Wrong state:** if the right block is there in the wrong state and a right-click would fix it (door, trapdoor or gate open/closed, lever, repeater delay, note block pitch, comparator mode), it right-clicks (vanilla's own interaction, repeated until the state matches). Otherwise it breaks and replaces the block.
12. **In the way:** if a player or mob stands where a block goes, it waits. After about 10 seconds it tells the owner, then reminds every few minutes. It never places a block inside itself.
13. **Missing items mid-build** (taken out, or the world changed): pause, tell the owner exactly what is missing, and continue as soon as it shows up in its inventory or supply chests. With `skip-missing` it skips that block instead.
14. **Tampering:** before finishing, it re-checks the area and fixes differences. Each position is fixed at most N times (config, default 3), so griefing one spot can't loop it forever. Positions over the limit are reported at the end. Then it tells the owner it finished, the job ends and it stays where it is.
15. **Speed:** player pace by default, with a config multiplier.

### Feedback

- `/journeymen builder status <name>`: phase (walking, clearing, building, paused), percent done, what it is waiting for, how long it has been stuck, and when it last got unstuck.
- A boss bar shows progress to the owner while within 64 blocks of the builder, e.g. `Bob · house2.nbt · placing 45/92`. It is green while working and yellow while paused.
- Pause messages go to the owner in chat at any distance, and are also written to the server log. If the owner is offline, they are saved with the world and delivered on login (repeats collapsed, at most 20 kept); `/journeymen builder notices` shows them too. While paused it reminds every few minutes.

### Config

A small config file read by `common/`, same format on both loaders, no library dependency and no in-game GUI for now. Keys include the speed multiplier, chunk loading (off), the redo limit (3), the max job volume, the max walking distance to a site, the reminder interval and the stuck time before a builder teleports out (`stuck_teleport_seconds`, 20, 0 = off).

## Phase 4: survival UI

Everything below also stays possible with a command (see Architecture rules).

### Recruiter's Stall

- A craftable item (shaped recipe: wool across the top, planks + emerald + planks, sticks at the bottom corners; the emerald is the `c:gems/emerald` tag so TFC's cut emerald works too; a normal data recipe so modpacks can replace it). Placed like a bed: one right-click builds a 3 wide, 2 deep, 3 tall multi-block stall facing the player, if that space is free. Vanilla textures only: plank counter across the front, spruce posts, striped wool awning on top.
- Breaking any part removes the whole stall and drops one stall item. Explosions drop one item too.
- Placing it spawns a **Recruiter** (villager look) who stands behind the counter. When the stall is gone, the recruiter disappears. The recruiter can be killed; break the stall and place it again to get a new one.
- Anyone can hire from any stall. Right-clicking the recruiter opens the hiring screen: the price, a random builder name you can edit, and a Hire button. The new builder appears in front of the counter and belongs to the player who paid.
- Price (config): `hire_cost_item`, an item id or an item tag (default `#c:gems/emerald`, which covers vanilla and TFC emeralds), `hire_cost_base` (10) plus `hire_cost_step` (5) for every builder the player already has, capped by `max_builders_per_player` (5, 0 = no cap). Ops summoning by command bypass the cap.
- Commands: `/journeymen recruiter hire [name]` hires from the nearest recruiter (within 8 blocks) at the same price; `/journeymen stall place <pos> [facing]` (ops) places a stall without the item.

### Builder screen

Right-clicking your builder (owner or op) opens one screen with tabs:

- **Inventory:** its 27 slots and 4 armor slots, plus your inventory. Shift-clicking armor in your inventory puts it in the matching armor slot, like your own inventory does.
- **Job:** with a job: the file, phase, progress and what it's waiting for, and Cancel job (click twice). Without one: a schematic picker (server `schematics/` folder) with the material list, the `corner` and `allow-breaking-enchanted` options, and Place (starts placement mode).
- **Chests:** assigned supply and dump chests with remove buttons. Link mode: click "Link supply chests" or "Link dump chests", then right-click chests in the world (each click toggles a chest; linked chests are outlined, supply green and dump orange). Right-clicking the builder ends it and reopens its screen on the Chests tab; right-clicking another builder ends it too and opens that builder's screen as usual. It also ends by itself after a minute without a click.
- **Orders:** follow / stay, rename, notices, dismiss (kill, with a confirmation).

### Placement mode

Picking a schematic and clicking Place closes the screen and shows a ghost preview (translucent blocks and the bounding box) at your crosshair. Sneak + scroll rotates, right-click confirms, sneak + right-click cancels. Confirming opens a confirmation screen with the pre-check result and Start (only when the check passes), Move (back to placement) and Cancel. When the check finds blocks that can't be placed by hand or missing materials, a checkbox for `skip-unplaceable` or `skip-missing` appears for each, and ticking one runs the check again. No item is involved.

The server sends the schematic's blocks compressed (run-length encoded, deflated, split into packets of at most 512 KiB). The client builds the ghost once per rotation on a background thread, leaving out faces hidden by a neighbouring schematic block, keeps it in a GPU vertex buffer and re-sorts it for transparency in the background, so moving the crosshair costs almost nothing. Schematics with more than 250,000 blocks, or whose ghost would need more than 1,000,000 faces, show only their bounding box. Fluids and blocks drawn only by a block entity renderer (chests, signs, banners) don't show in the ghost.

### Phase 4 steps

| Step | Done when |
|---|---|
| 4a ✅ | Networking on both loaders, Recruiter's Stall (item, recipe, multi-block, recruiter), hiring screen and `recruiter hire` / `stall place` commands, hire price config |
| 4b ✅ | Builder screen with tabs; Inventory tab with armor slots, shift-click armor, armor rendering and wear |
| 4c ✅ | Orders and Chests tabs, chest link mode with outlines |
| 4d ✅ | Job tab, placement mode with ghost preview, confirmation screen |

## Example schematics

`examples/schematics/` holds example schematics, generated by `examples/make_schematics.py` (Python, no dependencies). Regenerate them after changing the script and copy them into the dev run folders' `schematics/` (`neoforge/run`, `neoforge/run-tfc`, `fabric/runs/client`, `fabric/runs/server`). The script writes byte-identical files when nothing changed. They double as end-to-end test cases: supply a builder with `builder supply`, build, then `builder check` should report 0 to place. `garden_shed.litematic` has two regions, one with a negative size; `old_hut.schematic` is a 1.12 MCEdit file with a WorldEdit offset and the block entities the 1.13 conversion needs (bed color, flower pot, sign); `uploaded/Steve/watchtower.nbt` sits in a subfolder like a Create upload.

## Roadmap

| Phase | Done when |
|---|---|
| 0 ✅ | Empty mod builds and launches on NeoForge and Fabric |
| 1 ✅ | Summon a Steve-skinned builder by command, open its inventory, and it survives reload |
| 2 ✅ | Load `.schem` / `.nbt` (later also `.litematic` and legacy `.schematic`) and print a material list for a schematic |
| 3 ✅ | Builder builds a schematic as described in Build jobs, Pre-check, Supply and dump chests, How a build plays out and Feedback |
| 4 | Survival UI: Recruiter's Stall and hiring, builder screen, placement mode (see Phase 4) |

Phase 3 is built in steps, each tested on both loaders:

| Step | Done when |
|---|---|
| 3a ✅ | Config file, builder names and renaming, `<owner>.<name>` targeting for ops, `come` / `follow` / `stay` / `kill` / `status`, item pickup, damage and regeneration, fake player on both loaders |
| 3b ✅ | `check` / `build` / `cancel` with origin, rotation and options, chest assignment, the full pre-check, and jobs that survive reload |
| 3c ✅ | Clearing: walking into reach, breaking with tools at player speed, drops into inventory, dump chests, pausing for fluids |
| 3d ✅ | Placing: order, states, special cases, growth stages, made blocks, block entity data, fluids last, wrong-state fixing, waiting for entities, tampering and redo |
| 3e ✅ | Boss bar, pause notifications with offline delivery and reminders |

Scaffolding and pillaring were dropped: the builder reaches any height (see Reach).

Not tested yet (no way to test them so far):

- Other players hurting a builder when PvP is off (the builder should take no damage).
- A following builder getting unstuck towards its owner: the owner must be a real player in the player list, which a fake player from `debug as` isn't. The same recovery is tested with `come` (spot goal), chests, the site and `unstick`.
- Spawn protection and claim/protection mods (FTB Chunks, Open Parties and Claims, …) blocking the pre-check and the builder's breaking and placing. FTB Chunks is in the TFC test set, but claiming needs a real player in a team: fake players have none, and `ftbchunks admin claim_as` doesn't find server teams from the console.
- The phase 4b–4d client side by a person in game: the builder screen's tabs and slots, armor rendering, link mode outlines, rotating with sneak + scroll and the confirmation screen. Their server side is tested headlessly with `debug action`, `debug use` and `debug interact`, and the placement preview's rendering with the preview test file (screenshots).
- The placement preview with Sodium/Embeddium or Iris shader packs. It draws its own vertex buffer with the translucent block shader in the loader's after-translucent hook and depends on no vanilla renderer internals, but nobody has looked at it with them.

Backlog (no phase yet): mirroring schematics in the build command, counting Unbreaking in the pre-check durability estimate instead of ignoring it, placing entities from schematics (item frames, paintings, armor stands), more jobs per builder (a queue), several builders on one job, an early material check in the builder screen's Job tab (have/need for the picked schematic before the player goes to place it; design not decided yet).

## Related mods (stay distinct from these)

- **Buildlings** (NeoForge 1.21.1): builder NPCs that pull materials from chests.
- **MineColonies**: builder citizens as part of a full colony system.
