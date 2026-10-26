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

Regenerates the test schematics (`gen_schematics.py`), then runs every suite on each loader through `server_test.ps1` and prints a summary per loader (suite times, timeouts, failures). Full output: `build/testing/regress-<loader>.txt`. NeoForge and Fabric run the general suites, the last of which (`test_crew_restart_a`) leaves a crew job running when the server stops; a second server start then runs `-AfterRestart` (`test_crew_restart_b`, output in `regress-<loader>-restart.txt`), the one suite that relies on another's leftovers; TFC runs `test_tfc_farm`, `test_tfc_shelf` and `test_tfc_collapse`. `test_tfc_smithy` needs the owner's schematic, so run it by hand with `server_test.ps1 -Loader tfc`.

The whole regression takes about 5 minutes (NeoForge about 2 minutes, Fabric about 1½ minutes, TFC about 45 s, server startups included), plus a second server start each for NeoForge's and Fabric's restart pair. On a busy machine it can take two or three times as long. Gradle compiling first adds to that, which is why each loader should be its own command.

### `smoke.ps1`

```
smoke.ps1 -Loader neoforge|fabric|tfc
```

Starts a dev client, waits for `Sound engine started`, then stops it and kills leftover dev `java.exe`. Reports `STARTED`, `CRASHED`, `EXITED` or `TIMEOUT` (5 minutes) and the first mod-related log lines. Log: `build/testing/<loader>-client.log`.

### `gen_schematics.py`

Writes the test schematics into the server run dirs' `schematics/` (`neoforge/run`, `neoforge/run-tfc`, `fabric/runs/server`, whichever exist), or into the folders given as arguments. It reuses the writer from `examples/make_schematics.py`.

- All run dirs: `test_solid` (26×4×26 checkerboard), `test_fill` (52×1×52), `test_box` (13×5×13 hollow box of 32 block kinds), `test_restock` (32×2×32), `test_skip` (stone, fire, planks), `test_hang` (wall torches, scaffolding with sand), `test_missing.schem` (4×2×4 with farmland) and `test_row` (60 oak planks in a row).
- `neoforge/run-tfc` also gets `test_tfc_shelf.schem`.
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

A timeout prints `TIMEOUT waiting for …` and a failure `FAIL waiting for …` between `!!!` lines, with the line number. The file **stops there**: its remaining lines are skipped (`!!! STOPPED <file> at line <n>`), because later waits in the wrong state would only sit out their timeouts. Before the next file the harness stops any sprint, unfreezes the game (`tick unfreeze`), kills all foremen and builders and sets every fake player the file switched with `debug gamemode` back to `survival`. The next file in the same server start runs as usual. A file that runs to its end leaves its journeymen and jobs in the world (saved at the server stop; `test_crew_restart_a` relies on this), so every file kills them first, and messages from such leftover jobs can still reach its log until its first `mark`. A file with a syntax error (a `sleep` line, a bad regex, a `waitfor` without `=~`/`!~`) fails before any of its lines run (`NOT RUN`). The run ends with the lists of timeouts and failures (`file:line`) and a non-zero exit code; stopped files are marked in the per-file summary. Each wait prints how long it took in seconds and ticks.

- RCON only returns output produced while the command runs. Async `debug as` output that arrives later is appended by the server to the RCON reply of the next command, which may be one of the harness's own (`tick query`, `time query gametime`). It is always in the server log, so check results there (with `waitlog`).
- Under TFC, `/time query gametime` doesn't exist, so tick counts are left out of the output.

### Writing a suite

Start each file by setting up its own area: `forceload add` the area, `kill @e[type=journeymen:foreman]` and `kill @e[type=journeymen:builder]`, `ticks 40`, then `fill` a floor and clear the air above it. Pick an area no other suite uses (see the table below). Things to know:

- A server with no players stops ticking entities 15 seconds after the last player leaves unless chunks are force-loaded, so always force-load the test area.
- Force-loads may not survive a restart in the dev setup; each file adds its own.
- `fill` refuses more than 32768 blocks; split bigger areas, or old test builds stay in the way.
- Every job needs a foreman with a crew: `foreman summon <foreman>` (through `debug as` with permission 2), then `builder summon <foreman> <builder>` for each builder, `foreman chest <foreman> add …`, `foreman supply <foreman> …` and `foreman build <foreman> …`. Put the foreman outside the site, within 8 blocks of its edge (he walks back to that post anyway), and not where another suite's job will place blocks. Builders' names and foremen's names share one namespace per owner.
- Messages about a job come from the foreman (`[Steve.SolBoss] I finished building …`); a builder's own problems name it (`Dumpy's inventory is full. …`).
- Fake players aren't in the player list, so their chat (screen action replies, hire messages to the owner) isn't logged; command output is. Wait for `[debug action]` lines instead of a screen action's chat reply.
- Commands that need a player (like `foreman summon`) run through `debug as` (below). Their output arrives in the server log, so wait for it with `waitlog`.
- The fake player from `debug as` is the same one the player's builders break and place with. Its inventory lasts for the whole server run, so `clear @s` first when a test pays for something; tests that count a player's journeymen (hiring limits) use their own player name (`Hirer`) so other suites' journeymen don't count. If you switch it to creative with `debug gamemode`, set it back to `survival` before anything builds.

## Suites

| File | Loaders | Area (force-loaded) | Covers |
|---|---|---|---|
| `test_examples` | NeoForge, Fabric | 176..240 | 4 example builds, block entity data, redstone, farm |
| `test_restock` | NeoForge, Fabric | 480..560 | supply and dump trips; about 345 placed after 1800 ticks, dirt dumped, planks kept |
| `test_solid` | NeoForge, Fabric | 630..675 | a 26×4×26 solid block to completion |
| `test_bs_full` | NeoForge, Fabric | 290..330 × 310..360 | the owner's Blacksmith build to completion |
| `test_stuck` | NeoForge, Fabric | 796..884 | six teleports out of pits and boxes (`come`, chests, site, `unstick`, a foreman walking to his post), jobs finish |
| `test_box` | NeoForge, Fabric | 796..884 | 32 block kinds in a hollow box |
| `test_fill` | NeoForge, Fabric | 600..700 | a 52×52 floor, progress every 400 ticks |
| `test_formats` | NeoForge, Fabric | 296..367 | Litematica, MCEdit, subfolder files and path checks |
| `test_skip` | NeoForge, Fabric | 400..432 | `skip-unplaceable` and `skip-missing` |
| `test_4b` | NeoForge, Fabric | 288..352 | builder screen actions (the Job and Chests ones act on its foreman), armor, link mode, renaming and dismissing a foreman |
| `test_linkend` | NeoForge, Fabric | 288..352 | ending link mode (timeout, right-clicking his builder, another foreman's builder or the foreman) |
| `test_hang` | NeoForge, Fabric | 992..1040 | a wall torch after its wall, sand after the scaffolding under it, a torch with no wall skipped |
| `test_supply_missing` | NeoForge, Fabric | 1088..1151 | `foreman supply … missing-only` with and without a file gives exactly the missing materials and tools; all or nothing when the chest and inventory are full; the creative "Supply missing" screen action is refused in survival and works in creative |
| `test_dump_gone` | NeoForge, Fabric | 1200..1232 | dump chest messages: full, gone, full and gone together, keeping the rest after clearing |
| `test_crew` | NeoForge, Fabric | 1300..1380, 1498..1502 × 1312..1316 | a foreman's crew: his builders' inventories and his supply chests and tools pooled in the pre-check, refusals (busy, overlap, too far, another player's foreman, a builder named as a foreman, a name taken, `come`/`follow` during a job), an op addressing another player's foreman, a builder too far from the site waiting and joining once it is close, a dead builder dropping out, chunks unloading and loading, cancel, the same job continuing |
| `test_crew_build` | NeoForge, Fabric | 1600..1710 | crews working one shared plan: `cottage` by two, `wheat_farm` by three (one hoe), `test_solid` by four, a stone hill with obsidian cleared by three (one diamond pickaxe), all ending with 0 to place and no block placed where another builder claimed; reservations (50 planks for a 60-block row: 30 and 20 reserved, 10 skipped with `skip-missing`); one builder dying mid-batch while the others finish; emptied supply chests pausing the crew with "I'm missing" until `supply … missing-only` |
| `test_foreman` | NeoForge, Fabric | 1760..1840 | hiring: foremen from a stall (price steps, not enough emeralds, `max_foremen_per_player`, a name taken), builders from a foreman (price steps, too far from him, `max_builders_per_player`, another player); op summons up to `max_crew_size` and the full crew refusing summon, hire and adopt; a killed foreman's notice and orphans; adopting (refusals: full, already in a crew, another player's builder, a foreman); an old-save builder (job and chests in its NBT) loading as an orphan with its inventory |
| `test_foreman_gone` | NeoForge, Fabric | 1856..1935 × 1840..1935, 1896..1903 × 1984..1999 | a foreman unloaded mid-build: his builders wait until he loads, then finish; a foreman killed mid-build: the notice, orphans, another foreman adopting them and finishing from what is built; a builder unloaded while its foreman dies loads as an orphan |
| `test_crew_speed` | NeoForge, by hand | 1990..2230 | the same build by 1, 2 and 4 builders (`test_box`, `test_solid`, the owner's Blacksmith); the ticks of each `I finished building` wait are the result. Needs the Blacksmith schematic |
| `test_crew_restart_a`, `_b` | NeoForge, Fabric | 1400..1440 × 1300..1340 | a foreman's job saved by a server stop (`_a`, last suite) and finished by his crew after the restart (`_b`, run by `run_regress.ps1` in a second server start) |
| `test_tfc_farm` | TFC | 0..64 | `tfc_farm.schem` end to end (TFC grass, farmland with crops, path, water, copper chain, pumpkin) with large vessels as supply and dump chests |
| `test_tfc_shelf` | TFC | 64..127 × 0..63 | a dry-run `check` over floating raw granite leaves it alone; a fire brick shelf goes in after its wall, one with no wall is skipped |
| `test_tfc_collapse` | TFC | 140..191 × 0..95 | clearing 49 floating raw granite almost always starts a cave-in; the fallen rock is cleared again and the smooth stone floor, large vessel and builder come through |
| `test_tfc_smithy` | TFC, by hand | 192..271 × -16..79 | the owner's smithy with its fire brick shelves, to completion |

`test_stuck` and `test_box` share an area, as do `test_4b` and `test_linkend`; each clears it first.

## Debug commands

Available only in the dev environment, under `/journeymen debug` (permission 2):

- `debug as <player> <permission> <pos> <command>`: runs a command as a fake player with that name and permission level, standing at `pos`, and copies its output to the server log. Use it for commands that need a player, like `foreman summon`.
- `debug action <journeyman entity> <action> <pos> <text>` (run through `debug as`): sends a screen action as that player and logs the journeyman's state (`[debug action] … alive= following= job= chests=`, the chests being its foreman's). Actions: `follow`, `stay`, `rename`, `dismiss`, `notices`, `schematic_list`, `schematic_info`, and the foreman actions `cancel_job`, `remove_chest`, `link_supply`, `link_dump`, `check`, `start`, `supply_missing`, which on a builder act on its foreman. In the text, `|` becomes a line break and `-` means empty. For `check` and `start` the text is `<file>|<rotation>|<options>`.
- `debug use <pos>` (through `debug as`): a right-click on a block, for chest link mode.
- `debug interact <entity>` (through `debug as`): a right-click on an entity (on a foreman or builder it ends link mode). It logs whether link mode is still on and which builder screen tab opened (`none` for the fake player, which can't open screens).
- Not a command, but dev-only too: crew builders log each batch they claim (`[crew] Steve.Ann claims 30 blocks and reserves 30 Oak Planks`, `[crew] Steve.Cal claims 12 blocks to clear`), and any builder placing where a crewmate holds the claim logs a warning ending `which another builder claimed`, which crew suites use as a fail pattern.
- `debug gamemode <player> <mode>`: sets that fake player's game mode (e.g. `creative` for `supply_missing`, which logs a `[supply]` line). Set it back to `survival` before anything builds.

## Running a dev server by hand

Prefer `server_test.ps1`. If you need a server for an investigation:

- The run dir (`neoforge/run`, `neoforge/run-tfc`, `fabric/runs/server`) needs `eula.txt`.
- Gradle doesn't pass console input through to the server, so RCON is the way in: set `enable-rcon=true`, `rcon.port` and `rcon.password` in its `server.properties`, and turn RCON off again afterwards.
- `/tick sprint <ticks>` runs the game as fast as it can; `/tick rate <n>` also works but gains less. Set the rate back to 20 afterwards.
- To reproduce something from the owner's singleplayer save, copy the save into the run dir, point `level-name` in `server.properties` at the copy, and set it back afterwards. Never run the original. The owner isn't online there, so `forceload add` around the journeymen first.
- Stop the server and kill leftover dev `java.exe` when done (the scripts match on `devlaunch`, `DevLaunchInjector`, `BootstrapLauncher`, `net.neoforged.fml` or `fabricmc` in the command line, and leave Gradle daemons and the Java language server alone).

## Client checks

A dev client opens a game window. `smoke.ps1` checks that it starts. Two hook files in the client's run dir (`neoforge/run`, `neoforge/run-tfc` or `fabric/runs/client`; dev environment only) drive a client without a person, save screenshots to `screenshots/` and quit. Delete the file and the world afterwards, or every dev client launch runs it again.

### Placement preview: `journeymen-preview-test.properties`

The client creates a flat creative world, summons a foreman and a builder in his crew, starts placement mode for each schematic in turn at the crosshair, logs frame times (`[preview test]` lines: without the preview, with it, and with the crosshair moving) and saves a screenshot per rotation to `screenshots/<out>_<file>_<rotation>.png`.

Keys: `schematic` (comma-separated files), `options`, `rotations` (comma-separated, default `none`), `frames` (default 300), `pos`, `yaw` and `pitch` (where to stand, default `-60 40 -60`, `-45`, `40`; the crosshair reaches 64 blocks), `out` (default `preview`), `world` (default `jm-preview-test`) and `quit` (default `true`).

### Builder and confirmation screens: `journeymen-screen-test.properties`

Keys: `schematic` (default `cottage.schem`), `out` (default `screen`), `world` (default `jm-screen-test`), `quit`. The client creates a flat creative world, summons a foreman with 14 supply chests and a builder in his crew, opens the builder's screen (its Job and Chests tabs act on the foreman until phase 6b), picks the schematic on the Job tab, drags the material scrollbar to the end and scrolls the file list (`<out>_job.png`), scrolls the Chests tab (`<out>_chests.png`), runs the pre-check (`<out>_confirm.png`), drags its scrollbar to the end (`<out>_confirm_scrolled.png`), clicks "Supply missing (creative)" and waits for Start to turn on (`<out>_confirm_supplied.png`), logging `[screen test]` lines. Scrollbars only show when a list is longer than its box, so use a small window (the default 854×480) to see the confirmation screen's.

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
