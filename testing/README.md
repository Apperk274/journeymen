# Headless testing

This folder holds the headless regression suites and the tools that run them (PowerShell and Python, no dependencies). Everything the player can do also has a command (see the architecture rules in `CLAUDE.md`), so a dev server driven over RCON can test almost all of the mod without a person. Logs and outputs go to `build/testing/`.

Run the scripts from PowerShell in the repo root. Command file names are looked up relative to the current directory first, then in `testing/`.

```powershell
testing\server_test.ps1 -Loader neoforge -CommandsFile test_hang.txt,test_skip.txt
testing\run_regress.ps1 -Loaders neoforge
testing\smoke.ps1 -Loader fabric
```

## Rules

- **No real-time `sleep`** in test or investigation command files: a `sleep` line fails the file before it runs. Use the sprinting waits (`ticks`, `waitlog`, `waitfor`) so the server runs as fast as it can. Use `nosprint` or `realsleep` only where real time is genuinely needed (see below), and say why in your report.
- **While working on a change**, run only the relevant suite(s) on one loader. **Once at the end**, run the full regression on all loaders.
- **Never chain several server runs in one shell command** that could exceed the tool's 10-minute limit. Run them as separate commands, e.g. `run_regress.ps1 -Loaders neoforge`, then `-Loaders fabric`, then `-Loaders tfc`.
- **Clean up:** stop every background process, watcher, server and client you start before finishing, kill leftover dev `java.exe`, and leave RCON off. The scripts do this themselves; if you start something by hand, it's yours to stop.
- Each command file sets up its own area and must not rely on another file's leftovers.

## Scripts

### `server_test.ps1`

```
server_test.ps1 -Loader neoforge|fabric|tfc -CommandsFile a.txt[,b.txt …] [-Rate 20] [-LogPattern <regex>]
```

Starts the dev server through Gradle (`:neoforge:runServer`, `:fabric:runServer` or `:neoforge:runTfcServer`), turns RCON on in its `server.properties` (port 25575, password `journeymen-dev`), waits for `RCON running`, runs the command files one after another against that one server with `rcon.py`, stops the server, restores `server.properties` and kills leftover dev `java.exe`.

- Startup takes about 20 seconds, so put several files in one run.
- A file stops at its first timeout or failure (see Output); the next file still runs.
- After each file it prints that file's server log lines (default `-LogPattern`: the mod's `[...]` lines, exceptions and journeymen errors), its wall time and game ticks. At the end it lists every timeout and failure (with file and line) and exits non-zero if there were any.
- Server log: `build/testing/<loader>-server.log`.
- If the server isn't ready (crash, `BUILD FAILED`, exited), it prints the tail of the log instead.

### `run_regress.ps1`

```
run_regress.ps1 [-Loaders neoforge,fabric,tfc] [-Suites …] [-TfcSuites …]
```

Regenerates the test schematics (`gen_schematics.py`), then runs every suite on each loader through `server_test.ps1` and prints a summary per loader (suite times, timeouts, failures). Full output: `build/testing/regress-<loader>.txt`. NeoForge and Fabric run the general suites, the last of which (`test_crew_restart_a`) leaves two crew jobs running when the server stops; a second server start then runs `-AfterRestart` (`test_crew_restart_b`, output in `regress-<loader>-restart.txt`), the one suite that relies on another's leftovers; TFC runs `test_tfc_farm`, `test_tfc_shelf`, `test_tfc_collapse` and `test_tfc_made`. `test_tfc_smithy` needs the owner's schematic, so run it by hand with `server_test.ps1 -Loader tfc`.

The whole regression takes about 5 minutes (NeoForge about 2 minutes, Fabric about 1½ minutes, TFC about 45 s, server startups included), plus a second server start each for NeoForge's and Fabric's restart pair. On a busy machine it can take two or three times as long. Gradle compiling first adds to that, which is why each loader should be its own command.

### `smoke.ps1`

```
smoke.ps1 -Loader neoforge|fabric|tfc
```

Starts a dev client, waits for `Sound engine started`, then stops it and kills leftover dev `java.exe`. Reports `STARTED`, `CRASHED`, `EXITED` or `TIMEOUT` (5 minutes) and the first mod-related log lines. Log: `build/testing/<loader>-client.log`.

### `gen_schematics.py`

Writes the test schematics into the server run dirs' `schematics/` (`neoforge/run`, `neoforge/run-tfc`, `fabric/runs/server`, whichever exist), or into the folders given as arguments. It reuses the writer from `examples/make_schematics.py`.

- All run dirs: `test_solid` (26×4×26 checkerboard), `test_fill` (52×1×52), `test_box` (13×5×13 hollow box of 32 block kinds), `test_restock` (32×2×32), `test_skip` (stone, fire, planks), `test_hang` (wall torches, scaffolding with sand), `test_missing.schem` (4×2×4 with farmland), `test_row` (60 oak planks in a row), `test_dig` (32×2×32 of air, to clear), `test_soil` (64×2×32: air over the first 32 columns, dirt over the other 32, so clearing a dirt pile there drops what the job places), `test_putback` (27×2×8: a first row of 27 different blocks, one each, then terracotta, and a second layer of the same 27 kinds, 8 each) `test_redo` (16×4×16: a floor of oak and spruce planks in stripes along x, two layers of white wool and a layer of air) and `test_made` (7×2×3: in each row a dirt path and a farmland under stone, and a dirt path with air above).
- `neoforge/run-tfc` also gets `test_tfc_shelf.schem` and `test_tfc_made.schem` (`test_made` with fluvisol grass paths and farmland under querns).
- Not in the repo: `test_bs_full.txt` needs the owner's `Blacksmith_1339748.schematic` in the server run dir's `schematics/`, and `test_tfc_smithy.txt` needs the owner's `TFC_Smithy_Complex.schem` in `neoforge/run-tfc/schematics`.

The example schematics (`examples/schematics/`, from `examples/make_schematics.py`) must also be copied into the run dirs; `test_examples` and `test_formats` use them.

### `rcon.py`

Runs command files over RCON (`rcon.py [--port] [--password] [--log <server log>] [--log-pattern] [--rate] [--stop] files…`). `server_test.ps1` calls it; you rarely need it directly.

## Command files

Each line is a server command, or one of the harness commands below. `#` lines and blank lines are skipped, and `{q}` becomes `"` (for commands whose quoting would otherwise get in the way). Commands between waits run at `-Rate` (default 20 ticks per second), so setup behaves as in normal play. Each file starts at that rate and ends with the rate back at 20.

### Waits

Game logic, including the mod's timers (stuck teleport, link mode timeout, reminders, waiting for entities), runs on game ticks, so the waits use `/tick sprint`: the server runs as fast as the machine can and gives the same results per tick as at 20 ticks per second (checked against a real-time run; measured 1000–3000 ticks per second with builders working, 50–150× real time). Async work (schematic loading, pre-checks, supplies) and chunk loading take wall-clock time, not ticks, so wait for their result rather than counting ticks.

- `ticks <n> [timeout]`: lets exactly `n` game ticks pass at sprint speed. Use it where the test measures something at a point in game time (e.g. `test_restock` counts blocks placed after 1800 ticks). Default timeout: `max(10, n / 100)` seconds.
- `waitlog [timeout] [nosprint] [x<count>] <regex>`: sprints until `count` (default 1) server log lines since the file started (or since the last `mark`) match. This is how to wait for async output: `debug as` results, supplies (`\] Supplied \d+ items`), pre-checks (`\d+ to clear, \d+ to place`) and what foremen tell their owner. Example: `waitlog 20 \[Steve\.SolBoss\] I finished building`.
- `waitfor [timeout] [nosprint] <command> =~ <regex>` (or `!~`): sprints and repeats the command until its output matches (or stops matching). Example: `waitfor 5 nosprint execute if block 5 200 2 tfc:ceramic/large_vessel =~ passed`.
- `mark`: later `waitlog`s only look at log lines from here on (e.g. before a final `check`, so the build's own pre-check line doesn't count).
- `nosprint`: keeps the file's normal rate while waiting. Use it only for a short async wait right before a `ticks` measurement, so the measurement starts at the right game time (e.g. `waitlog 30 nosprint Rex is on the way` before `ticks 1800`), or for a quick check of state that must not move on in the meantime.
- `realsleep <seconds>`: waits in real time at the file's normal rate. Plain `sleep` is rejected: the file fails before its first line runs, with a message pointing to `ticks`, `waitlog` and `waitfor`.
- `forceload add …` lines also wait (sprinting, up to 30 s) until every chunk of the area is loaded with its entities (`execute if loaded`), so a following `kill` sees journeymen left from an earlier run.

Timeouts are wall-clock seconds. A `waitlog` or `waitfor` without one waits up to 10 s; a leading number is always read as the timeout, so a regex that starts with a number word needs an explicit timeout in front. Sprinting builds and async results take well under a second to a few seconds, so the default fits almost every wait. Give an explicit timeout only where a wait takes longer (the biggest builds, `test_solid` and `test_bs_full` at about 4 s and the TFC smithy at about 4–6 s, get 20 s) or should be shorter (5 s for a `waitfor` on state that should already be there). Keep timeouts at a few times what the wait takes: a wrong wait then costs seconds, not minutes.

### Fail patterns

Either wait can end with `!! <failRegex>`, or a bare `!!` for the default set:

```
can't start this job|\] I'm missing |\] I need |isn't allowed to build|Exception
```

When a server log line since the command before the wait matches, the wait stops at once with a loud `FAIL waiting for …` instead of sitting out its timeout. Job waits (`I finished building`, `crew is on the way`) use `!!`. Add the messages that would mean the test went wrong, e.g. `waitlog 60 … I finished building clear_area\.schem !! is gone`.

### Output

A timeout prints `TIMEOUT waiting for …` and a failure `FAIL waiting for …` between `!!!` lines, with the line number. The file **stops there**: its remaining lines are skipped (`!!! STOPPED <file> at line <n>`), because later waits in the wrong state would only sit out their timeouts. Before the next file the harness stops any sprint, unfreezes the game (`tick unfreeze`), kills all foremen and builders, sets every fake player the file switched with `debug gamemode` back to `survival`, and runs `journeymen config reload` if the file changed the config with `debug config`. The next file in the same server start runs as usual. A file that runs to its end leaves its journeymen and jobs in the world (saved at the server stop; `test_crew_restart_a` relies on this), so every file kills them first, and messages from such leftover jobs can still reach its log until its first `mark`. A file with a syntax error (a `sleep` line, a bad regex, a `waitfor` without `=~`/`!~`) fails before any of its lines run (`NOT RUN`). The run ends with the lists of timeouts and failures (`file:line`) and a non-zero exit code; stopped files are marked in the per-file summary. Each wait prints how long it took in seconds and ticks.

- RCON only returns output produced while the command runs. Async `debug as` output that arrives later is appended by the server to the RCON reply of the next command, which may be one of the harness's own (`tick query`, `time query gametime`). It is always in the server log, so check results there (with `waitlog`).
- Under TFC, `/time query gametime` doesn't exist, so tick counts are left out of the output.

### Writing a suite

Start each file by setting up its own area: `forceload add` the area, `kill @e[type=journeymen:foreman]` and `kill @e[type=journeymen:builder]`, `ticks 40`, then `fill` a floor and clear the air above it. Pick an area no other suite uses (see the table below). Things to know:

- A server with no players stops ticking entities 15 seconds after the last player leaves unless chunks are force-loaded, so always force-load the test area.
- Force-loads may not survive a restart in the dev setup; each file adds its own.
- `fill` refuses more than 32768 blocks; split bigger areas, or old test builds stay in the way.
- `setblock` with the block that is already there fails ("Could not set the block") and empties a container on the way, so set `air` first when refilling a chest. Put chests inside the area the file clears, or the previous run's chest is in the way.
- Journeymen left unloaded somewhere (another dimension, an area no longer force-loaded) keep their registry entry and with it their name, so bring them back into the test area before the file ends.
- Every job needs a foreman with a crew: `foreman summon <foreman>` (through `debug as` with permission 2), then `builder summon <foreman> <builder>` for each builder, `foreman chest <foreman> add …`, `foreman supply <foreman> …` and `foreman build <foreman> …`. Put the foreman outside the site, within 8 blocks of its edge (he walks back to that post anyway), and not where another suite's job will place blocks. Builders' names and foremen's names share one namespace per owner.
- Messages about a job come from the foreman (`[Steve.SolBoss] I finished building …`); a builder's own problems name it (`Dumpy's inventory is full. …`).
- Fake players aren't in the player list, so their chat (hire messages to the owner, notices) isn't logged; command output is. A screen action run through `debug action` logs each reply it sends as a `[debug reply]` line, so wait for those (or the `[debug action]` state line).
- Commands that need a player (like `foreman summon`) run through `debug as` (below). Their output arrives in the server log, so wait for it with `waitlog`.
- The fake player from `debug as` is the same one the player's builders break and place with. Its inventory lasts for the whole server run, so `clear @s` first when a test pays for something; tests that count a player's journeymen (hiring limits) use their own player name (`Hirer`) so other suites' journeymen don't count. If you switch it to creative with `debug gamemode`, set it back to `survival` before anything builds.

## Suites

| File | Loaders | Area (force-loaded) | Covers |
|---|---|---|---|
| `test_examples` | NeoForge, Fabric | 176..240 | 4 example builds, block entity data, redstone, farm; the cottage job's placing total equals its pre-check's "to place" |
| `test_restock` | NeoForge, Fabric | 480..560 | supply and dump trips; about 345 placed after 1800 ticks, dirt dumped, planks kept |
| `test_solid` | NeoForge, Fabric | 630..675 | a 26×4×26 solid block to completion |
| `test_bs_full` | NeoForge, Fabric | 290..330 × 310..360 | the owner's Blacksmith build to completion |
| `test_stuck` | NeoForge, Fabric | 796..884 | six teleports out of pits and boxes (`come`, chests, site, `unstick`, a foreman walking to his post, moved into his pit once his job has started because `build` refuses a foreman with no way to the site), jobs finish |
| `test_box` | NeoForge, Fabric | 796..884 | 32 block kinds in a hollow box |
| `test_fill` | NeoForge, Fabric | 600..700 | a 52×52 floor, progress every 400 ticks |
| `test_formats` | NeoForge, Fabric | 296..367 | Litematica, MCEdit, subfolder files and path checks |
| `test_skip` | NeoForge, Fabric | 400..432 | `skip-unplaceable` and `skip-missing` |
| `test_4b` | NeoForge, Fabric | 288..352 | builder screen actions (follow, stay, rename, dismiss; job and chest actions refused), armor, link mode, check, start and cancel from the foreman's screen, renaming and dismissing a foreman |
| `test_linkend` | NeoForge, Fabric | 288..352 | ending link mode (timeout, right-clicking his builder, another foreman's builder or the foreman, which reopens his screen on the Chests tab) |
| `test_hang` | NeoForge, Fabric | 992..1040 | a wall torch after its wall, sand after the scaffolding under it, a torch with no wall skipped |
| `test_supply_missing` | NeoForge, Fabric | 1088..1151 | `foreman supply … missing-only` with and without a file gives exactly the missing materials and tools; all or nothing when the chest and inventory are full; the creative "Supply missing" screen action is refused in survival and works in creative |
| `test_dump_gone` | NeoForge, Fabric | 1200..1232 | dump chest messages: full, gone, full and gone together, keeping the rest after clearing |
| `test_crew` | NeoForge, Fabric | 1300..1380, 1498..1502 × 1312..1316 | a foreman's crew: his builders' inventories and his supply chests and tools pooled in the pre-check, `max_chests` refusing a chest over the limit, refusals (busy, overlap, too far, another player's foreman, a builder named as a foreman, a name taken, `come`/`follow` during a job), an op addressing another player's foreman, a builder too far from the site waiting and joining once it is close, a dead builder dropping out, chunks unloading and loading, cancel, the same job continuing |
| `test_crew_build` | NeoForge, Fabric | 1600..1710 | crews working one shared plan: `cottage` by two, `wheat_farm` by three (one hoe), `test_solid` by four, a stone hill with obsidian cleared by three (one diamond pickaxe), all ending with 0 to place and no block placed where another builder claimed; reservations (50 planks for a 60-block row: 30 and 20 reserved, 10 skipped with `skip-missing`); one builder dying mid-batch while the others finish; emptied supply chests pausing the crew with "I'm missing" until `supply … missing-only` |
| `test_foreman` | NeoForge, Fabric | 1760..1840 | hiring: foremen from a stall (price steps, not enough emeralds, `max_foremen_per_player`, a name taken), builders from a foreman (price steps, too far from him, `max_builders_per_player`, another player); op summons up to `max_crew_size` and the full crew refusing summon, hire and adopt; a killed foreman's notice and orphans; adopting (refusals: full, already in a crew, another player's builder, a foreman); an old-save builder (job and chests in its NBT) loading as an orphan with its inventory |
| `test_foreman_gone` | NeoForge, Fabric | 1856..1935 × 1840..1935, 1896..1903 × 1984..1999 | a foreman unloaded mid-build: his builders wait until he loads, then finish; a foreman killed mid-build: the notice, orphans, another foreman adopting them and finishing from what is built; a builder unloaded while its foreman dies loads as an orphan |
| `test_crew_speed` | NeoForge, by hand | 1990..2230 | the same build by 1, 2 and 4 builders (`test_box`, `test_solid`, the owner's Blacksmith); the ticks of each `I finished building` wait are the result. Needs the Blacksmith schematic |
| `test_foreman_screen` | NeoForge, Fabric | 2304..2368 | every foreman screen action: hiring (not enough emeralds, too far, name taken, price steps, another player), adopting (unknown, already in the crew, an orphan, crew full), link mode started from the screen and ended on the foreman (Chests tab) or a builder, removing chests, check, start (refused, then after creative `supply_missing`; survival refused), follow/stay refused during the job, a builder refusing job orders, cancel, follow, stay, rename, notices (owner only), dismiss leaving orphans |
| `test_site_path` | NeoForge, Fabric | 2432..2527 | the foreman's path to the site in `check` and `build`: boxed in glass and on a platform he can't get down from (refused, `none` in the `[path]` line), the box opened and a staircase added (passes, `found`), and the site behind a 3-high wall ring 9 blocks out with him on the big floor outside (the search reaches its edge: passes with the warning, `unknown`) |
| `test_keep_chunks` | NeoForge, Fabric | 2560..2623 | `keep_chunks_loaded`: after `forceload remove all` (every forceload in the overworld, so no forced chunk keeps entities ticking) a crew with its supply chests 2 chunks from the site builds to completion, the site and the journeymen's chunks are entity-ticking (`execute if loaded`); the tickets go (`execute if loaded` fails, `debug chunks`) after finishing, cancelling, `config reload` turning the option off and the foreman's death; with the option off the chunks unload, and turning it on loads the unloaded foreman and builders back from their saved positions and they finish |
| `test_far_chests` | NeoForge, Fabric | 2688..2879 × 2688..2719 | supply chests far from the site: a builder walks 158 blocks to its only supply chest (longer than the old fixed 30 s limit) and finishes; a crew whose first supply chest sits on a pillar out of reach gives up on it, the foreman says `My builders can't get to the supply chest at …` once, both builders fetch from the far chest instead and finish, and the pillar chest keeps its planks |
| `test_tools` | NeoForge, Fabric | 2944..3007 | how many tools builders carry while clearing 2048 dirt (`test_dig`): by hand still clearing after 34000 ticks; with 54 stone shovels in supply a builder takes 13, dumps and restocks, never carries 14 or more and finishes within 34000 ticks; its only shovel breaking with lots left fetches the 5 added meanwhile; a crew of two sharing 4 shovels takes 2 each; a builder holding 27 shovels with no room in supply gets the "carries 27 tools, 19 more than it needs" pause (not "dump chests are full"), then returns 19 once there is room and finishes; with its first supply chest full and a second one empty it returns the 19 into the second one without pausing |
| `test_soil` | NeoForge, Fabric | 3072..3199 × 3072..3135 | clearing 2048 dirt for a job that places 2048 dirt: with all of it in supply every drop is dumped and every tool restock counts 27 free slots; with 1024 in supply and `skip-missing` the 1024 drops supply lacks are stored into it and placed, the rest dumped; with no dirt in supply and the first two supply chests full the drops go into the third one (no pause) and none are dumped; a crew of two with 1024 in supply stores and dumps the same way; all end with 0 to place. The ticks of each `The site … is clear` wait measure clearing speed |
| `test_putback` | NeoForge, Fabric | 3264..3359 × 3264..3343 | fetching for the next blocks in building order: the first fetch fills its 27 slots with one of each kind of the first row, the terracotta next to the supply chests comes from the second supply chest, and it places what it carries before fetching more (it used to pick the nearest terracotta and pause with "inventory is full of things it still needs"); starting with all 243 blocks of the 27 kinds in its inventory and an empty first supply chest, it stores what the next blocks don't need there, fetches the terracotta and finishes; the same with a crew of two, one of them carrying the 243 blocks. All end with 0 to place |
| `test_redo_store` | NeoForge, Fabric | 3392..3487 × 3392..3455 | drops and picked-up items the rest of the job needs go into a supply chest, never the dump, when the stock has none left: a builder alone whose redo pass breaks two swapped planks and picks up a plank taken out of the floor; a builder clearing wool over a finished floor while a plank of that floor is taken out and dropped next to it with 26 other items that fill its inventory (the plan made before clearing doesn't know the plank is needed again); a crew of three with the swap, extra planks to clear and picked-up planks; the floor case with a crew of three. All end with 0 to place |
| `test_made` | NeoForge, Fabric | 3520..3615 × 3520..3583 | a dirt path or farmland under a solid block, which the game turns back into dirt, is planned as dirt: the material list and `check` show only the shovel uses of the path with air above and no hoe, `supply … missing-only` gives no hoe; a builder alone and a crew of three (with a path and a farmland already under two of the stone spots, which count as done) build it with exactly that supply, without a missing-items pause and without dumping dirt, the spots under stone end as dirt and the free path as a path, and `check` reports 0 to place |
| `test_dump_source` | NeoForge, Fabric | 3648..3743 × 3648..3711 | dump chests as a last-resort source: `check` and `build` still count 10 planks only in the dump chest as missing; once a job has started, planks moved from the supply chest into the dump chest are fetched from it, only the shortfall and after supply (a builder alone, alone with `skip-missing`, which must not skip them, and a crew of three), never dumped again, with 0 to place at the end; with the planks in neither, the pause says `and my dump chests don't have them either` and the job finishes once they are in the supply chest |
| `test_lifecycle_edges` | NeoForge, Fabric | 3776..3903, and 3776..3871 × 3776..3807 in the Nether | a builder on a job not going through a nether portal; a builder `/tp`ed to the Nether waiting there (`waiting in another dimension`) without building in it and finishing once back; a foreman `/tp`ed there pausing the job with a message while his crew waits, and carrying on once back; cancelling the job of a foreman unloaded in the Nether; an owner logging in under a new name (`debug rejoin`) keeping it on his foreman; a builder frozen in a loaded chunk that doesn't tick (just outside the force-loaded area) dropping out of the crew while the other finishes; a foreman standing in another job's site walking out of it, an idle foreman on a spot where a block goes stepping aside, `unstick` never putting a journeyman into another job's site |
| `test_lifecycle_chests` | NeoForge, Fabric | 4096..4223 × 3776..3903, 4400..4403 × 3800..3803 | a supply chest inside the site and a dump chest whose other half is inside it failing the pre-check; the pre-check counting only the inventories of builders that can take part; double chests (`chest remove` with the other half, the assigned half broken, two single chests joining counted once); a builder whose only supply chest is gone told so, not that the supply chests have no room; items from a cleared chest that don't fit kept (not dropped) when the job is cancelled and dumped on the next job |
| `test_crew_restart_a`, `_b` | NeoForge, Fabric | 1400..1440 × 1300..1340, 1472..1535 × 1360..1391 | a foreman's job saved by a server stop (`_a`, last suite) and finished by his crew after the restart (`_b`, run by `run_regress.ps1` in a second server start); a job's skipped blocks saved too: the 1024 stones skipped with `skip-missing` before the stop stay skipped after it though stone is in the supply chest by then |
| `test_tfc_farm` | TFC | 0..64 | `tfc_farm.schem` end to end (TFC grass, farmland with crops, path, water, copper chain, pumpkin) with large vessels as supply and dump chests |
| `test_tfc_shelf` | TFC | 64..127 × 0..63 | a dry-run `check` over floating raw granite leaves it alone; a fire brick shelf goes in after its wall, one with no wall is skipped |
| `test_tfc_collapse` | TFC | 140..191 × 0..95 | clearing 49 floating raw granite almost always starts a cave-in; the fallen rock is cleared again and the smooth stone floor, large vessel and builder come through |
| `test_tfc_made` | TFC | 320..383 × 320..383 | `test_made` with TFC soils: fluvisol grass paths and farmland under querns are placed as fluvisol dirt by a crew of three with the exact supply, no hoe, 0 to place |
| `test_tfc_smithy` | TFC, by hand | 192..271 × -16..79 | the owner's smithy with its fire brick shelves, to completion |

`test_stuck` and `test_box` share an area, as do `test_4b` and `test_linkend`; each clears it first.

## Debug commands

Available only in the dev environment, under `/journeymen debug` (permission 2):

- `debug as <player> <permission> <pos> <command>`: runs a command as a fake player with that name and permission level, standing at `pos`, and copies its output to the server log. Use it for commands that need a player, like `foreman summon`.
- `debug action <journeyman entity> <action> <pos> <text>` (run through `debug as`): sends a screen action as that player, logs every reply it sends to the player as `[debug reply] …` and then the journeyman's state (`[debug action] … alive= following= job= crew=[…] chests=[…]`, crew and chests being its foreman's for a builder). Actions on any journeyman: `follow`, `stay`, `rename`, `dismiss`, `schematic_list`, `schematic_info`. Foreman only (on a builder they are refused with `Builders take no orders about jobs, chests or crews.`): `notices`, `cancel_job`, `remove_chest`, `link_supply`, `link_dump`, `check`, `start`, `supply_missing`, `hire` (text: the new builder's name) and `adopt` (text: the orphan's name). In the text, `|` becomes a line break and `-` means empty. For `check` and `start` the text is `<file>|<rotation>|<options>`; in the dev environment the result logs `[check] <player> for <foreman>: <header> -> passes|fails`.
- `debug use <pos>` (through `debug as`): a right-click on a block, for chest link mode.
- `debug interact <entity>` (through `debug as`): a right-click on an entity (on a foreman or builder it ends link mode). It logs whether link mode is still on and the open menu (`none` for the fake player, which can't open screens). Which screen and tab the right-click asked for is logged in the dev environment as `[screen] <player> opens <name>'s screen on the <tab> tab` (a foreman's Chests tab when it ended his own link mode, otherwise his Job tab; a builder's Inventory tab).
- Not a command, but dev-only too: crew builders log each batch they claim (`[crew] Steve.Ann claims 30 blocks and reserves 30 Oak Planks`, `[crew] Steve.Cal claims 12 blocks to clear`), and any builder placing where a crewmate holds the claim logs a warning ending `which another builder claimed`, which crew suites use as a fail pattern.
- Not a command either: each tool restock logs `[tools] Steve.Digger at <chest> takes <n> and returns <n> tools: Stone Shovel 13/13 for 2048 blocks; 27 slots, 13 tools carried` (per tool kind: carried/target for its remaining blocks, `/<builders>` in a crew; the slots it counts as room; a hoe or shovel fetched for farmland or a path logs `[tools] Steve.Digger at <chest> takes <n> tools`), and a builder out of a tool that only speeds a block up logs `[tools] Steve.Digger has no tool for Dirt: Stone Shovel saves about <n> ticks, a trip takes about <n> ticks, so it fetches more|digs by hand`.
- Not a command either: every dump and every store into a supply chest logs `[unload] Steve.Digger dumps|stores <n> items at <chest>: Dirt 1536, …` (`nothing` when it moved none), every fetch of materials or tools `[fetch] Steve.Solo takes <n> items from the supply|dump chest at <chest>: Oak Planks 10, …`, and every item a builder picks up from the ground `[pickup] Steve.Stale picks up <n> <item> at <pos>`. These and the tool restock lines are also written outside the dev environment, at DEBUG level, so a production server has them in `logs/debug.log` (NeoForge) but not in `latest.log`.
- `debug config <key> <value>`: sets a config value in memory until the next `journeymen config reload` (which rereads the file); the file isn't written. Used for `keep_chunks_loaded` and `max_chests`. Logs `[debug config] <key> = <value> until the next config reload`.
- `debug chunks`: lists the chunk tickets `keep_chunks_loaded` holds per dimension and, while the option is on, each job's site chunks and the chunk of each journeyman on it (`[debug chunks] Steve.Keeper keeps 4 site chunks of test_box.schem in minecraft:overworld and Keeper at 162 162 in …`). Also replied to the caller, so `waitfor` can match it.
- Not a command either: every foreman path check in a pre-check logs `[path] Steve.Pathy to the site: found|none|unknown after <n> nodes in <ms> ms`.
- `debug gamemode <player> <mode>`: sets that fake player's game mode (e.g. `creative` for `supply_missing`, which logs a `[supply]` line). Set it back to `survival` before anything builds.
- `debug rejoin <player> <name>`: runs the mod's login handling for `<player>`'s UUID under a new profile name, as if the player had changed names, and logs `[debug rejoin] <player> joined as <name>`.

## Running a dev server by hand

Prefer `server_test.ps1`. If you need a server for an investigation:

- The run dir (`neoforge/run`, `neoforge/run-tfc`, `fabric/runs/server`) needs `eula.txt`.
- Gradle doesn't pass console input through to the server, so RCON is the way in: set `enable-rcon=true`, `rcon.port` and `rcon.password` in its `server.properties`, and turn RCON off again afterwards.
- `/tick sprint <ticks>` runs the game as fast as it can; `/tick rate <n>` also works but gains less. Set the rate back to 20 afterwards.
- To reproduce something from the owner's singleplayer save, copy the save into the run dir, point `level-name` in `server.properties` at the copy, and set it back afterwards. Never run the original. The owner isn't online there, so `forceload add` around the journeymen first.
- Stop the server and kill leftover dev `java.exe` when done (the scripts match on `devlaunch`, `DevLaunchInjector`, `BootstrapLauncher`, `net.neoforged.fml` or `fabricmc` in the command line, and leave Gradle daemons and the Java language server alone).

## Client checks

A dev client opens a game window. `smoke.ps1` checks that it starts. Three hook files in the client's run dir (`neoforge/run`, `neoforge/run-tfc` or `fabric/runs/client`; dev environment only) drive a client without a person, save screenshots to `screenshots/` and quit. Delete the file and the world afterwards, or every dev client launch runs it again.

### Placement preview: `journeymen-preview-test.properties`

The client creates a flat creative world, summons a foreman and a builder in his crew, starts placement mode for each schematic in turn at the crosshair, logs frame times (`[preview test]` lines: without the preview, with it, and with the crosshair moving) and saves a screenshot per rotation to `screenshots/<out>_<file>_<rotation>.png`.

Keys: `schematic` (comma-separated files), `options`, `rotations` (comma-separated, default `none`), `frames` (default 300), `pos`, `yaw` and `pitch` (where to stand, default `-60 40 -60`, `-45`, `40`; the crosshair reaches 64 blocks), `out` (default `preview`), `world` (default `jm-preview-test`) and `quit` (default `true`).

### Builder and confirmation screens: `journeymen-screen-test.properties`

Keys: `schematic` (default `cottage.schem`), `out` (default `screen`), `world` (default `jm-screen-test`), `start` (default `true`), `quit`. The client creates a flat creative world, summons a foreman `Boss` with 14 supply chests and two builders (`Shot`, `Ann`), and two orphans (`Orph`, `Ivy`, whose foreman is killed). It right-clicks the foreman, picks the schematic on the Job tab, drags the material scrollbar to the end and scrolls the file list (`<out>_job.png`), scrolls the Chests tab (`<out>_chests.png`), shows the Crew tab (`<out>_crew.png`) and the Orders tab (`<out>_orders.png`), then right-clicks the builder for its Inventory and Orders tabs (`<out>_builder_inventory.png`, `<out>_builder_orders.png`). It runs the foreman's pre-check (`<out>_confirm.png`), drags its scrollbar to the end (`<out>_confirm_scrolled.png`), clicks "Supply missing (creative)" and waits for Start to turn on (`<out>_confirm_supplied.png`), and unless `start=false` presses Start and reopens the foreman's Job tab with the job running (`<out>_job_running.png`) and his Crew tab (`<out>_crew_running.png`), logging `[screen test]` lines. Scrollbars only show when a list is longer than its box, so use a small window (the default 854×480) to see the confirmation screen's.

### Config screen: `journeymen-config-test.properties`

Keys: `out` (default `config`), `world` (default `jm-config-test`), `quit`. On the title screen the client presses Mods, types `Journeymen` in the search box and clicks the first entry (`<out>_mods_filtered.png` before the click). On NeoForge that selects the mod (`<out>_mods.png`, the Config button's label and `active` state logged) and the hook presses Config; on Fabric (run with `-Pwith_modmenu=true`) Mod Menu's click on the mod's icon opens the screen directly. In the config screen (`<out>_menu.png`) it enters invalid values for `max_chests`, `speed_multiplier` and an unknown item for `hire_cost_item` (`<out>_menu_invalid.png`, Save off), then valid ones and Save; it logs the live values and the file's keys. It then creates a flat world, opens the screen in single player (`<out>_singleplayer.png`), saves `max_crew_size = 2` and `keep_chunks_loaded = true`, logs what the server thread sees and summons a foreman with three builders (the third is refused, `crew is full (2 builders)` in the log's chat lines). Last it shows the read-only screen used on a remote server (`<out>_server_readonly.png`) and the editable one scrolled to the end (`<out>_scrolled.png`), writes the config file back to what it was before the run and quits. Lines start with `[config test]`; the screen itself logs `Config saved from the config screen (<mode>): {key=old -> new, …}`. The config file is shared with the dev server in `neoforge/run`, so keep a copy if a run might not finish.

## TerraFirmaCraft

The owner's modpack is TerraFirma Rebirth 2 (NeoForge 21.1.248, TFC 4.2.9 and many TFC addons), so changes are also tested with TFC. TFC is NeoForge-only; Fabric only has to keep working without it.

- NeoForge has two extra runs, `runTfcServer` and `runTfcClient`, that use `neoforge/run-tfc` (git-ignored) instead of `neoforge/run`. The scripts use them with `-Loader tfc`.
- `mise exec -- ./gradlew :neoforge:syncTfcMods` copies the jars listed in `neoforge/tfc-test-mods.txt` (TFC, Patchouli, Firmalife, ArborFirmaCraft, FarmersTFC + Farmer's Delight, TFC Real World, FTB Chunks with its libraries, WorldEdit, Cold Sweat with its TFC integration, TFC Trough) from the CurseForge instance into `run-tfc/mods`. Pass `-Ptfc_instance=<path>` if the instance isn't at the default CurseForge location. Production NeoForge jars run unchanged in the dev environment.
- The run dir needs `eula.txt`, a `schematics/` folder (the examples plus the owner's TFC schematics) and its own config.
- `examples/schematics/tfc_farm.schem` is the TFC end-to-end case (grass, farmland with crops, a path, water, a copper chain and a pumpkin).

### What TFC changes for tests

- **Stack caps:** TFC caps many stack sizes (dirt stacks to 32), so `item replace … 64` fails.
- **Unified items:** the pack turns vanilla emeralds, buckets and metals into TFC items.
- **Soils:** `tfc:dirt/<soil>`, `tfc:grass/<soil>`, `tfc:farmland/<soil>` and `tfc:grass_path/<soil>` (soils such as `fluvisol`, `entisol`).
- **Storage:** barrels and large vessels have no vanilla container, only a NeoForge item handler. Large vessels pop when the block under them goes.
- **Cave-ins:** mining raw rock can start a cave-in (10% per block) that drops unsupported `tfc:can_collapse` blocks within up to 19 blocks. That tag includes `c:stones`, so vanilla stone too: a stone test floor with air under it caves in, taking placed blocks, large vessels and the builder with it. TFC test areas therefore use a `minecraft:smooth_stone` floor (not in the tag). `test_tfc_farm` keeps its raw granite on that floor, where it can't fall, and `test_tfc_collapse` covers cave-ins on purpose.
- **No game time query:** `/time query gametime` doesn't exist under TFC, so the harness prints no tick counts there.
- **Claims:** FTB Chunks is in the set, but claiming needs a real player in a team: fake players have none, and `ftbchunks admin claim_as` doesn't find server teams from the console. Claim protection is still untested.
