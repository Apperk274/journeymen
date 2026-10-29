param(
    [string[]]$Loaders = @("neoforge", "fabric", "tfc"),
    [string[]]$Suites = @("test_examples.txt", "test_restock.txt", "test_solid.txt", "test_bs_full.txt", "test_stuck.txt", "test_box.txt",
        "test_fill.txt", "test_formats.txt", "test_skip.txt", "test_4b.txt", "test_linkend.txt", "test_hang.txt", "test_supply_missing.txt",
        "test_dump_gone.txt", "test_crew.txt", "test_crew_build.txt", "test_foreman.txt", "test_foreman_gone.txt", "test_foreman_screen.txt",
        "test_site_path.txt", "test_keep_chunks.txt", "test_far_chests.txt", "test_tools.txt", "test_soil.txt", "test_putback.txt", "test_redo_store.txt",
        "test_made.txt", "test_dump_source.txt", "test_lifecycle_edges.txt", "test_lifecycle_chests.txt", "test_security.txt", "test_security_build.txt",
        "test_fluids.txt", "test_physics.txt", "test_states.txt", "test_growth.txt", "test_schematic_edges.txt", "test_crew_restart_a.txt"),
    [string[]]$AfterRestart = @("test_crew_restart_b.txt", "test_schematic_edges_restart.txt"),
    [string[]]$TfcSuites = @("test_tfc_farm.txt", "test_tfc_shelf.txt", "test_tfc_collapse.txt", "test_tfc_made.txt")
)
$repo = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repo "build\testing"
New-Item -ItemType Directory -Force $outDir | Out-Null
python (Join-Path $PSScriptRoot "gen_schematics.py")
$started = Get-Date
$summary = "^test_.*: \d+ s|TIMEOUT|FAIL|STOPPED|NOT RUN|no timeouts|no failures|not ready"
foreach ($loader in $Loaders) {
    $files = if ($loader -eq "tfc") { $TfcSuites } else { $Suites }
    $out = Join-Path $outDir "regress-$loader.txt"
    $t = Get-Date
    & (Join-Path $PSScriptRoot "server_test.ps1") -Loader $loader -CommandsFile $files *> $out
    "{0}: {1:N0} s -> {2}" -f $loader, ((Get-Date) - $t).TotalSeconds, $out
    Get-Content $out | Where-Object { $_ -cmatch $summary }
    if ($loader -ne "tfc" -and $AfterRestart.Count -gt 0) {
        $out = Join-Path $outDir "regress-$loader-restart.txt"
        $t = Get-Date
        & (Join-Path $PSScriptRoot "server_test.ps1") -Loader $loader -CommandsFile $AfterRestart *> $out
        "{0} after restart: {1:N0} s -> {2}" -f $loader, ((Get-Date) - $t).TotalSeconds, $out
        Get-Content $out | Where-Object { $_ -cmatch $summary }
    }
}
"all: {0:N0} s" -f ((Get-Date) - $started).TotalSeconds
