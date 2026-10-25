param(
    [string[]]$Loaders = @("neoforge", "fabric", "tfc"),
    [string[]]$Suites = @("test_examples.txt", "test_restock.txt", "test_solid.txt", "test_bs_full.txt", "test_stuck.txt", "test_box.txt",
        "test_fill.txt", "test_formats.txt", "test_skip.txt", "test_4b.txt", "test_linkend.txt"),
    [string[]]$TfcSuites = @("test_tfc_farm.txt")
)
$repo = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repo "build\testing"
New-Item -ItemType Directory -Force $outDir | Out-Null
python (Join-Path $PSScriptRoot "gen_schematics.py")
$started = Get-Date
foreach ($loader in $Loaders) {
    $files = if ($loader -eq "tfc") { $TfcSuites } else { $Suites }
    $out = Join-Path $outDir "regress-$loader.txt"
    $t = Get-Date
    & (Join-Path $PSScriptRoot "server_test.ps1") -Loader $loader -CommandsFile $files *> $out
    "{0}: {1:N0} s -> {2}" -f $loader, ((Get-Date) - $t).TotalSeconds, $out
    Get-Content $out | Where-Object { $_ -match "^test_.*: \d+ s|TIMEOUT|no timeouts|not ready" }
}
"all: {0:N0} s" -f ((Get-Date) - $started).TotalSeconds
