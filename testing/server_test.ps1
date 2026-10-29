param(
    [ValidateSet("neoforge", "fabric", "tfc")][string]$Loader = "neoforge",
    [Parameter(Mandatory = $true)][string[]]$CommandsFile,
    [string]$LogPattern = "Journeymen/\]: \[|\(Journeymen\) \[|Exception|ERROR.*journeymen",
    [double]$Rate = 20
)
$repo = Split-Path -Parent $PSScriptRoot
$runDir = switch ($Loader) {
    "neoforge" { Join-Path $repo "neoforge\run" }
    "tfc" { Join-Path $repo "neoforge\run-tfc" }
    default { Join-Path $repo "fabric\runs\server" }
}
$task = if ($Loader -eq "tfc") { ":neoforge:runTfcServer" } else { ":${Loader}:runServer" }
$logDir = Join-Path $repo "build\testing"
New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir "$Loader-server.log"
Remove-Item $log, "$log.err" -ErrorAction SilentlyContinue
$files = @($CommandsFile | ForEach-Object { if (Test-Path $_) { (Resolve-Path $_).Path } else { Join-Path $PSScriptRoot $_ } })
$missing = @($files | Where-Object { -not (Test-Path $_) })
if ($missing.Count -gt 0) { throw "missing commands file(s): $($missing -join ', ')" }
function Remove-Unreadable([string]$path) {
    if (Test-Path -LiteralPath $path) {
        icacls $path /reset /q | Out-Null
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}
$unreadable = Join-Path $runDir "schematics\test_edge_unreadable"
Remove-Unreadable $unreadable
$props = Join-Path $runDir "server.properties"
$original = [System.IO.File]::ReadAllText($props)
$started = Get-Date
$proc = $null
try {
    if ($files | Where-Object { (Split-Path $_ -Leaf) -eq "test_schematic_edges.txt" }) {
        New-Item -ItemType Directory -Force $unreadable | Out-Null
        Copy-Item (Join-Path $runDir "schematics\test_edge_void.schem") (Join-Path $unreadable "hidden.schem")
        icacls $unreadable /deny "*S-1-1-0:(RD)" /q | Out-Null
    }
    $patched = $original -replace '(?m)^enable-rcon=.*$', 'enable-rcon=true' -replace '(?m)^rcon\.password=.*$', 'rcon.password=journeymen-dev' -replace '(?m)^rcon\.port=.*$', 'rcon.port=25575'
    [System.IO.File]::WriteAllText($props, $patched)
    $proc = Start-Process -FilePath "mise" -ArgumentList "exec", "--", "./gradlew.bat", $task, "--console=plain" `
        -WorkingDirectory $repo -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(900)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($text -match "RCON running") { $ready = $true; break }
        if ($text -match "(?i)crash report|BUILD FAILED|Exception in thread" -or $proc.HasExited) { break }
    }
    $startup = ((Get-Date) - $started).TotalSeconds
    if ($ready) {
        "server ready after $([int]$startup) s"
        $env:PYTHONIOENCODING = "utf-8"
        python (Join-Path $PSScriptRoot "rcon.py") --log $log --log-pattern $LogPattern --rate $Rate --stop @files
    } else {
        "server not ready after $([int]$startup) s"
        Get-Content $log -Tail 40
        Get-Content "$log.err" -Tail 20 -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(60)
    while (-not $proc.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
} finally {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match "devlaunch|DevLaunchInjector|BootstrapLauncher|net\.neoforged\.fml|fabricmc" -and $_.CommandLine -notmatch "GradleDaemon|GradleWrapperMain|redhat\.java" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    [System.IO.File]::WriteAllText($props, $original)
    Remove-Unreadable $unreadable
}
"total $([int]((Get-Date) - $started).TotalSeconds) s, server log: $log"
