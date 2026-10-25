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

## Testing

Test headlessly with the tools in `testing/`; **read `testing/README.md` before writing or running tests** (scripts, command-file syntax, waits, fail patterns, suites, debug commands, client screenshot hooks, TFC details). From PowerShell in the repo root:

```powershell
testing\server_test.ps1 -Loader neoforge|fabric|tfc -CommandsFile test_hang.txt[,…]   # suites against one dev server over RCON
testing\run_regress.ps1 -Loaders neoforge                                             # full regression for one loader
testing\smoke.ps1 -Loader fabric                                                      # dev client starts without crashing
```

- Never use real-time `sleep` in test or investigation command files (the harness rejects it). Use the sprinting waits (`ticks`, `waitlog`, `waitfor`) so the server runs as fast as it can; use `nosprint` or `realsleep` only when real time is genuinely needed, and say why in your report.
- While working on a change, run only the relevant suite on one loader. Run the full regression on all loaders (NeoForge, Fabric, TFC) once at the end.
- Never chain several server runs in one shell command that could exceed the tool's 10-minute limit; run them as separate commands (e.g. `run_regress.ps1` once per loader).
- Stop every background process, watcher, server and client you start before finishing, kill leftover dev `java.exe`, and leave RCON off. `server_test.ps1` turns RCON on and restores `server.properties` itself.
- Each command file sets up and force-loads its own area and doesn't rely on another file's leftovers. Commands that need a player run through the dev-only `/journeymen debug as`; screen buttons and clicks through `debug action`, `debug use`, `debug interact` and `debug gamemode`.
- To reproduce something from the owner's save, copy it into the run dir and point `level-name` at the copy. Never run the original.

### TerraFirmaCraft

The owner's modpack is TerraFirma Rebirth 2 (NeoForge 21.1.248, TFC 4.2.9 and many addons), so changes are also tested with TFC. `mise exec -- ./gradlew :neoforge:syncTfcMods [-Ptfc_instance=<path>]` copies the jars in `neoforge/tfc-test-mods.txt` from the CurseForge instance into `neoforge/run-tfc/mods`; `:neoforge:runTfcServer` / `runTfcClient` (loader `tfc` in the scripts) use that run dir. TFC is NeoForge-only, so Fabric only has to keep working without it. TFC caps stack sizes (dirt to 32), the pack unifies emeralds, buckets and metals into TFC items, TFC soils are `tfc:dirt|grass|farmland|grass_path/<soil>`, TFC storage (barrels, large vessels) has only a NeoForge item handler, and mining raw rock can start cave-ins that also take vanilla stone, so TFC test areas use a `minecraft:smooth_stone` floor.

## Architecture rules

- Put as much as possible in `common/`. It sees only vanilla Minecraft (Mojang mappings + Parchment), so no NeoForge or Fabric imports.
- Loader-specific code goes in `neoforge/` or `fabric/`: registration, entity renderer registration, commands registration, networking, menu types, config. Expose it to common through an interface in `common/.../platform/services` with a `META-INF/services` entry in each loader.
- Every feature has to work on both loaders.
- Other mods are reached through loader APIs, never through their classes: storage through NeoForge's item handler capability and Fabric's Transfer API, fluid containers through NeoForge's `FluidUtil` and Fabric's fluid storage, hoes and shovels through NeoForge item abilities (`ICompatHelper`). Rules about blocks come from the game itself (loot tables, tool actions, block states), not from lists of vanilla blocks, so modded blocks follow them too.
- `neoforge_version` in `gradle.properties` is also the minimum NeoForge version the mod declares, and must not be newer than the owner's modpack (21.1.248). Likewise `fabric_version` (Fabric API, 0.116.7) is the declared minimum in `fabric.mod.json`, and Fabric loader is pinned low (0.16.9). Don't bump these without a reason.
- A new `gradle.properties` key that resources need must be added to `expandProps` in `buildSrc/src/main/groovy/multiloader-common.gradle`.
- Everything the player can do must also be possible with a command, so every feature can be tested and debugged without its UI. Friendlier ways (recruiter, screen buttons, placement mode) never replace the command: builders can always be summoned with `/journeymen builder summon` and builds started with `/journeymen builder build …`.

## What the mod is

The player gets a **builder** (a journeyman) that takes a schematic and builds it **by hand**, like a survival player. Nothing appears out of thin air, and it is not creative mode. The name "Journeymen" leaves room for more trades (farmer, miner, …) as other journeyman types.

All commands live under `/journeymen`, with each trade as a subcommand (`/journeymen builder …`). `/journeymen help` lists them. There is no `/jm` alias, because JourneyMap owns `/jm`.

### Builder entity

- A custom `PathfinderMob` (not a villager) with its own AI goals, rendered with the vanilla player model and the default Steve skin.
- Has an owner (the player who summoned or hired it). Only the owner commands it. Operators (permission level 2+) can command any builder, addressing it as `<owner>.<name>` (e.g. `Steve.Gerald`).
- Has a name: a random first name unique among the owner's builders, or the one given in `summon [name]`. A name tag or `/journeymen builder rename <old> <new>` renames it. Commands address builders by name, and the name shows above its head. Names use only letters, digits, `_` and `-` (at most 24), so they never need quotes, and player names never contain the `.` separator.
- Has a 27-slot inventory and armor slots. It wears its armor (rendered), which protects it and wears out like a player's. Right-click opens the builder screen.
- Inventory, owner, name, assigned chests and current build job persist through save/load.
- Summoned with `/journeymen builder summon` (ops), or hired from a recruiter (see Recruiter's Stall).
- Can be hurt like a mob (falls, lava, mobs, other players under PvP rules), including by its owner, and regenerates slowly. On death it drops its inventory and armor. `/journeymen builder kill <name>` removes it and drops its inventory.
- Picks up items lying around itself (player pickup range), working or idle, but doesn't go out of its way for them.
- When idle it stays where it is. `builder come <name>` walks it to you; `builder follow <name>` / `builder stay <name>` toggle following.
- Walks through wooden doors and fence gates like a player: opens them on its way and closes them behind it if they were closed, leaving open ones open. Iron doors stay shut.
- **Getting unstuck** (like a tamed wolf, but by time stuck): while it is going somewhere (a chest it wants, the build site, the spot it picked to get within reach of the next blocks, the spot it was called to with `come`, or its owner when following and at least 12 blocks behind) and for `stuck_teleport_seconds` (20, 0 = off) hasn't got 0.5 blocks closer nor broken, placed or fetched anything, it teleports to a safe spot next to that goal.
  - A safe spot has floor, room to stand, no fluid, fire or leaves, is in an entity-ticking chunk of the same dimension, in sight of the goal (within chest reach of a chest, within reach of the site's edge, 2–3 blocks from its owner), closer to the goal than it is, preferably outside the job area and never where the job will still place a block or clear the floor.
  - Pauses (waiting for materials, a full dump chest, someone in the way) don't count as stuck time but don't reset it, so retrying an unreachable chest adds up. Working from where it is (see Reach) counts as progress.
  - It sounds and shows particles like a chorus fruit teleport and logs `Stuck for … teleported to …`. It tells its owner only after 3 teleports within 10 minutes (then at most once per reminder interval).
  - At least 30 s between teleports; with no safe spot it retries every 10 s, and the fallbacks (pausing for an unreachable chest or site) still apply. A builder told to `stay` never teleports by itself.
  - `/journeymen builder unstick <name>` does it at once: towards its current goal, the site (or a chest it is heading for) during a job, its owner when following, otherwise the player who ran the command.

### Schematics

- Formats: **Sponge `.schem`** (WorldEdit, v2 and v3), **Litematica `.litematic`**, **vanilla structure `.nbt`** and old **MCEdit / WorldEdit `.schematic`** (pre-1.13, numeric ids). Blocks and block entities from older Minecraft versions are upgraded with the game's own data fixers.
- `.litematic`: all regions are combined over their overall bounding box. Positions no region covers have no data and are left alone, like structure void.
- `.schematic`: legacy id and metadata are flattened the way the game converted 1.12 worlds to 1.13 (`BlockStateData` and the chunk upgrade, which also takes bed and banner colors, flower pot plants, skulls, note block notes and door halves from block entities and neighbors), then upgraded. Block entities are upgraded too. Ids above 255 (old modded blocks) and ids the game never had are reported as unknown blocks and left alone. A `.schematic` that is really a Sponge file is read as one.
- Files are read server side from `schematics/` in the game directory, including subfolders (e.g. Create's uploads in `schematics/uploaded/<player>/`). A schematic is named by its path relative to `schematics/` with `/` (e.g. `uploaded/Steve/house.nbt`); paths that leave the folder are refused. Names with `/` or spaces need quotes, and the suggestions offer them quoted.
- `/journeymen schematic list` lists them and `/journeymen schematic materials <file>` prints the material list.

### Build jobs

- `/journeymen builder build <name> <file> [<x y z>] [rotation] [options]` assigns a job. `/journeymen builder check` with the same arguments is a dry run that prints the pre-check. The arguments follow vanilla `/place template`: position defaults to where the player stands, rotation uses the vanilla names (`none`, `clockwise_90`, `180`, `counterclockwise_90`).
- **Origin:** by default the paste origin the file stores: the WorldEdit offset in a `.schem` or `.schematic` (`WEOffsetX/Y/Z`, like `//paste`), the litematic's own origin (region coordinates 0,0,0). `.nbt` files and `.schematic` files without an offset use the min corner. The `corner` option uses the min corner for any file.
- **Rotation:** 0, 90, 180 or 270 degrees clockwise around the origin; block states rotate with it.
- **Area:** the rotated bounding box. Schematic air is cleared; structure void and positions without data are left alone.
- **Options:** `corner`, `allow-breaking-enchanted`, `skip-unplaceable`, `skip-missing` (see Pre-check).
- **One job per builder.** `build` is refused while it has one. `builder cancel <name>` stops it and leaves the half-built structure; leftovers stay in its inventory and chests. Issuing the same job again continues from what is in the world, because the plan is a diff.
- **Getting there:** before walking to the site it fetches what the first stage needs from its supply chests (tools if there is anything to clear, otherwise materials for the first blocks plus any hoe or shovel for farmland and paths). `build` is refused if the site is farther than the configured distance or no path is found.
- **Chunks:** it stops when its chunks unload and continues when they load. A config option (off by default) lets a builder with a job keep its chunks loaded. It keeps working while the owner is offline.
- Limits: a config cap on job volume (default 256×256×256); two active jobs' areas may not overlap; `come` and `follow` are refused during a job.

### Pre-check (core requirement)

`build` and `check` run the same check. A job starts only when everything passes; otherwise it lists every problem at once. The check changes nothing in the world.

- **Materials:** every item needed for placing must already be in its inventory or supply chests; drops from clearing don't count. Only plain items count and are used: no custom name, enchantments, contents or other vanilla data (a renamed chest or filled shulker box is never placed). Data other mods add is ignored when matching, so TFC metal items (`tfc:heat`) and TFC food blocks (creation date) count.
- **Tools:** every block to clear that needs a tool tier needs such a tool, with enough durability for all the blocks it will break, counted conservatively (1 per block, Unbreaking ignored). 10 obsidian and a diamond pickaxe with 9 durability left fails.
- **Enchanted tools** are never used below 1 durability, so the check counts them as having 1 less; `allow-breaking-enchanted` lifts this. Plain tools can be used up.
- **Made blocks:** a block that drops another block which a hoe or shovel turns into it is made like a player would: farmland = dirt + hoe, dirt path = dirt + shovel, and the same for modded soils (TFC farmland and grass paths). The tool use counts toward durability; any tool that can till or flatten works.
- **Grass:** blocks with a `snowy` state (grass block, mycelium, podzol, TFC grass) are placed as the block they drop without Silk Touch (dirt, TFC dirt) and count as done once that is there; the grass spreads by itself. Blocks that drop no block (TFC clay grass drops clay) still need their item.
- **Can't be placed by hand:** blocks with no item (TFC's forge, fire, portals) fail the check. Blocks whose item survival can't get (TFC clay grass, bedrock) show up as missing materials.
- **Skipping:** `skip-unplaceable` leaves blocks that can't be placed by hand alone, like structure void. `skip-missing` lets the job start with materials missing; a block whose items are neither in its inventory nor supply chests is then skipped instead of pausing. The check names the option when its problem is present. Skipped blocks are reported when the job ends and not retried by the tampering pass. Tools are always required.
- **Unbreakable blocks** (destroy speed < 0: bedrock, barrier, end portal frame, reinforced deepslate, …) in the clearing area fail the check.
- **Protection:** it breaks and places through a fake player with the owner's identity, so claim and protection mods allow it exactly where the owner could build. The check asks spawn protection and the world border directly, and claim mods the way a player starts mining (NeoForge's left-click block event, Fabric's attack block callback, empty hand). It never fires block break or place events, because other mods act on them (TFC cave-ins and landslides). Positions the owner may not start mining fail; a position protected only against placing passes, and the builder skips it at build time and reports it at the end.
- Size and distance limits.

### Supply and dump chests

- `/journeymen builder chest <name> add <x y z> supply|dump`, plus `remove` and `list`. Several chests per role are used in order. Both halves of a double chest count as one. Any block with an item inventory works, including modded storage that only offers the loader's item API (TFC barrels and large vessels, drawers). In survival they are assigned with link mode (see Builder screen).
- **Supply chests** are stock: it takes the materials and tools it needs from them, and they count in the pre-check. It never builds from a dump chest and never dumps into a supply chest.
- **Dump chests** take drops when its inventory is full, and whatever it doesn't need once the site is clear. With no dump chest, or none it can use, it pauses and asks, naming each dump chest that is full, gone, not loaded or out of reach.
- `/journeymen builder supply <builder> [<file> [pos] [rotation] [options]]` (ops only, creates items) puts a job's full material list into the builder's supply chests, plus the cheapest plain tools that cover the job's tool uses (enough wooden pickaxes for the stone, a diamond pickaxe for obsidian, a hoe for farmland). Without a file it uses the current job. What doesn't fit goes to the player's inventory. All or nothing: if anything doesn't fit it gives nothing and says how much didn't fit.
- `missing-only` (in the options, or right after the builder name for its current job: `supply <builder> missing-only`) gives only what the pre-check finds missing: materials its inventory and supply chests lack, and tools for uncovered tool uses (one plain tool kind at a time, the one that can mine the most of the uncovered blocks first, so a diamond pickaxe for obsidian also covers the stone). Same placement and all-or-nothing rules; when nothing is missing it says so.

### How a build plays out (core requirement)

1. **Plan:** diff the schematic against the world into blocks to remove and blocks to place.
2. **Clear** the whole area first, top-down. Then place.
3. **Reach:** it walks until the block is within horizontal reach (4.5 blocks). Height doesn't matter: it reaches any height, so there is no scaffolding or pillaring. Of the blocks next in order it takes one within reach, otherwise the nearest one it can walk to within reach (a path search over the site), so it never waits on an unreachable block while others are reachable. Only when it can't get within reach of any (lava pool, void) does it work from where it is. The path search follows its walking rules: a partial block sticking up above its floor (TFC mold table or channel, carpet, flower pot) raises the floor if low enough to step onto, so climbing onto a block with one on top is too high a jump; taller ones are jumped onto like full blocks; it never plans to stand where its head would hit something. If it stops getting closer to the spot it picked, it gets unstuck; with that off or no safe spot, it works from where it is and doesn't search again from the same place for a while (5 s, doubling up to 30 s).
4. **Breaking:** look at the block, swing, show crack progress (`destroyBlockProgress`), with player break time for the held tool. It breaks through the owner's fake player with vanilla survival breaking, so tool wear, two-block blocks (doors, beds) and container contents behave as for a player.
   - Tools: the fastest tool it has for each block. For blocks that need a tool it uses the least versatile one that works (an iron pickaxe for stone when the diamond one is needed for obsidian), and uses a tool on other blocks only when that durability isn't reserved. It fetches useful tools from supply when clearing starts and again when it lacks one.
   - Drops go straight into its inventory, never on the ground; what doesn't fit is held (and saved) until it reaches a dump chest. When full it walks to a dump chest (if everything it carries is still needed, to a supply chest instead), keeping its tools and the materials the job still needs.
   - When the site is clear it takes everything else it collected to its dump chests even if not full; if they are full, gone, not loaded or out of reach it says so and keeps the rest; without a dump chest it keeps everything.
5. **Fluids in the way:** water or lava where the schematic wants a block or air is not handled. At the first one (in the same top-down order) it pauses, tells the owner the position, checks again every few seconds and continues once the fluid is gone.
6. **Placing:** bottom-up. It places anywhere within reach (no supporting neighbor or line of sight needed), straight into replaceable blocks (short grass, snow layers) like a player. Each block consumes its item with a swing and a sound. The block state (facing, half, axis, …) is kept and rotated. Special cases follow the material list (redstone wire → redstone, double slab → 2 slabs, potted plant → pot + plant, …); doors and beds place both halves from one item.
   - **Hanging blocks** come after all other blocks, each once its support can hold it. The game's survival rule decides which: a block that couldn't stay with only air around it (torches, doors, rails, redstone, carpets, plants, vines, lanterns, modded shelves, …), plus gravity blocks resting on one of them. One whose support never comes is skipped and reported, never placed to break or fall.
   - **Restocking:** it fetches the next blocks' materials from supply, as much as fits. When the next blocks need more room than it has free, it unloads in one trip (junk to a dump chest, job materials it won't need soon back into a supply chest), then fills up. If the supply chest is full it stops trying that for a minute and carries what fits.
   - **Getting around:** it may walk on the structure as it rises and drop up to 6 blocks (taking fall damage) when healthy. Along the site edge nearest its first supply chest it keeps a one-block-wide staircase notch of the schematic's own blocks (each step one higher, blocks above the steps left out), so it can walk back onto a raised structure. It fills the notch after all other non-hanging blocks, top step down, before the hanging blocks.
7. **Growth stages:** crops, saplings and other aging blocks are placed young (age 0) and grow on their own. Right after tilling farmland it plants the crop on top, so the farmland doesn't dry out. Leaves are placed persistent. States that come from neighbors (fence, pane and wall connections, stair shape, redstone wire connections and power, `lit`, `powered`) are not compared; a block counts as done in the state the game itself gives it at that spot from its neighbors and contents (e.g. a TFC shelf's item flags).
8. **Block updates** happen as for a player, so a half-built contraption may fire.
9. **Fluids last:** water and lava sources and waterlogging go in after every solid block, with any item holding a full bucket of that fluid (vanilla, TFC wooden and metal, other mods' buckets). The emptied container comes back to its inventory.
10. **Block entity data:** it copies sign text (with dye color and glow when it has the dye or glow ink sac), banner patterns (needs the patterned banner), and decor contents from stock (flower pot plants, lectern books, decorated pot item, chiseled bookshelf books, jukebox disc). Container contents are not copied. Entities (item frames, paintings, armor stands, mobs) are ignored.
11. **Wrong state:** if the right block is there in the wrong state and a right-click fixes it (door, trapdoor or gate open/closed, lever, repeater delay, note block pitch, comparator mode), it right-clicks with vanilla's interaction until the state matches. Otherwise it breaks and replaces the block.
12. **In the way:** if a player or mob stands where a block goes, it waits, tells the owner after about 10 seconds, then reminds every few minutes. It never places a block inside itself.
13. **Missing items mid-build** (taken out, or the world changed): pause, tell the owner exactly what is missing, and continue once it shows up in its inventory or supply chests. With `skip-missing` it skips the block instead.
14. **Tampering:** before finishing it re-checks the area and fixes differences, each position at most N times (config, default 3), so griefing one spot can't loop it. Positions over the limit are reported. Then it tells the owner it finished, the job ends and it stays where it is.
15. **Speed:** player pace, with a config multiplier.

### Feedback

- `/journeymen builder status <name>`: phase (walking, clearing, building, paused), percent done, what it is waiting for, how long it has been stuck, and when, from where and to where it last got unstuck (saved with the world).
- A boss bar shows progress to the owner within 64 blocks of the builder, e.g. `Bob · house2.nbt · placing 45/92`: green while working, yellow while paused.
- Pause messages go to the owner in chat at any distance and to the server log. If the owner is offline they are saved with the world and delivered on login (repeats collapsed, at most 20 kept); `/journeymen builder notices` shows them too. While paused it reminds every few minutes.

### Config

A small config file read by `common/`, same format on both loaders, no library dependency and no in-game GUI. Keys include the speed multiplier, chunk loading (off), the redo limit (3), the max job volume, the max walking distance to a site, the reminder interval, `stuck_teleport_seconds` (20, 0 = off) and the hire price keys.

## Survival UI

Everything below also stays possible with a command (see Architecture rules).

### Recruiter's Stall

- A craftable item (shaped data recipe so modpacks can replace it: wool across the top, planks + emerald + planks, sticks at the bottom corners; the emerald is the `c:gems/emerald` tag so TFC's cut emerald works). Placed like a bed: one right-click builds a 3 wide, 2 deep, 3 tall stall facing the player, if the space is free. Vanilla textures only: plank counter, spruce posts, striped wool awning.
- Breaking any part removes the whole stall and drops one stall item, explosions too.
- Placing it spawns a **Recruiter** (villager look) behind the counter, who disappears with the stall. The recruiter can be killed; break and replace the stall for a new one.
- Anyone can hire from any stall. Right-clicking the recruiter opens the hiring screen: price, an editable random builder name, and Hire. The new builder appears in front of the counter and belongs to the payer.
- Price (config): `hire_cost_item`, an item id or tag (default `#c:gems/emerald`), `hire_cost_base` (10) plus `hire_cost_step` (5) per builder the player already has, capped by `max_builders_per_player` (5, 0 = no cap). Ops summoning by command bypass the cap.
- Commands: `/journeymen recruiter hire [name]` hires from the nearest recruiter (within 8 blocks) at the same price; `/journeymen stall place <pos> [facing]` (ops) places a stall without the item.

### Builder screen

Right-clicking your builder (owner or op) opens one screen with tabs:

- **Inventory:** its 27 slots and 4 armor slots, plus yours. Shift-clicking armor from your inventory puts it in the matching armor slot.
- **Job:** with a job: file, phase, progress, what it's waiting for, and Cancel job (click twice). Without one: a schematic picker (server `schematics/`) with the material list, the `corner` and `allow-breaking-enchanted` options, and Place (starts placement mode).
- **Chests:** assigned supply and dump chests with remove buttons. Link mode: click "Link supply chests" or "Link dump chests", then right-click chests in the world (each click toggles; linked chests are outlined, supply green and dump orange). Right-clicking the builder ends it and reopens its screen on the Chests tab; right-clicking another builder ends it and opens that builder's screen. It ends by itself after a minute without a click.
- **Orders:** follow / stay, rename, notices, dismiss (kill, with confirmation).

Every list that can scroll (schematic files, material list, chests, the confirmation screen's pre-check result) shows a thin vanilla scrollbar when longer than its box; the wheel scrolls it, and the thumb can be dragged or the track clicked.

### Placement mode

Place closes the screen and shows a ghost preview (translucent blocks and the bounding box) at your crosshair. Sneak + scroll rotates, right-click confirms, sneak + right-click cancels. No item is involved.

Confirming opens a confirmation screen with the pre-check result and Start (only when it passes), Move (back to placement) and Cancel. When the check finds unplaceable blocks or missing materials, a `skip-unplaceable` / `skip-missing` checkbox appears for each, and ticking one re-runs the check. A player in creative also gets "Supply missing (creative)" when materials or tools are missing: the server re-checks creative mode and command rights (no op needed: a creative player can take any item anyway), supplies exactly what is missing like `builder supply … missing-only` (supply chests first, then the player's inventory, all or nothing), re-runs the check and shows the outcome at the top of the result and in chat.

The server sends the schematic compressed (run-length encoded, deflated, packets of at most 512 KiB). The client builds the ghost once per rotation on a background thread (hidden faces culled), keeps it in a GPU vertex buffer and re-sorts it in the background. Schematics over 250,000 blocks, or whose ghost would need over 1,000,000 faces, show only their bounding box. Fluids and blocks drawn only by a block entity renderer (chests, signs, banners) don't show.

## Example schematics

`examples/schematics/` holds example schematics generated by `examples/make_schematics.py` (Python, no dependencies; byte-identical output when nothing changed). After changing the script, regenerate them and copy them into the dev run folders' `schematics/` (`neoforge/run`, `neoforge/run-tfc`, `fabric/runs/client`, `fabric/runs/server`). They are end-to-end cases: supply a builder with `builder supply`, build, then `builder check` should report 0 to place. `garden_shed.litematic` has two regions, one with a negative size; `old_hut.schematic` is a 1.12 MCEdit file with a WorldEdit offset and the block entities the 1.13 conversion needs; `uploaded/Steve/watchtower.nbt` sits in a subfolder like a Create upload; `tfc_farm.schem` is the TFC case.

## Roadmap

Phases 0–4 are built: the mod on both loaders, the builder entity, schematic loading, building by hand (everything above), and the survival UI. Phase 4's client side still has to be checked in game by a person.

Not tested yet (no way to test them so far):

- Other players hurting a builder when PvP is off (it should take no damage).
- A following builder getting unstuck towards its owner: the owner must be a real player in the player list, which a `debug as` fake player isn't. The same recovery is tested with `come`, chests, the site and `unstick`.
- Spawn protection and claim mods (FTB Chunks, Open Parties and Claims, …) blocking the pre-check and the builder's breaking and placing. Claiming in FTB Chunks needs a real player in a team.
- The survival UI by a person in game: the Inventory tab's slots and shift-click, armor rendering, link mode outlines, rotating with sneak + scroll, and using the screens with mouse and keyboard. Server side is tested with the debug commands; the Job and Chests tabs, the confirmation screen and the placement preview are rendered and screenshotted by the client test hooks.
- The placement preview with Sodium/Embeddium or Iris shader packs. It draws its own vertex buffer with the translucent block shader in the loader's after-translucent hook and uses no vanilla renderer internals, but nobody has looked.

Backlog: mirroring schematics, counting Unbreaking in the pre-check durability estimate, placing entities from schematics (item frames, paintings, armor stands), a job queue per builder, several builders on one job, an early have/need material check in the Job tab (design not decided).

## Related mods (stay distinct from these)

- **Buildlings** (NeoForge 1.21.1): builder NPCs that pull materials from chests.
- **MineColonies**: builder citizens as part of a full colony system.
