param([ValidateSet("neoforge", "fabric", "tfc")][string]$Loader = "neoforge")
$repo = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repo "build\testing"
New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir "$Loader-client.log"
Remove-Item $log, "$log.err" -ErrorAction SilentlyContinue
$task = if ($Loader -eq "tfc") { ":neoforge:runTfcClient" } else { ":${Loader}:runClient" }
$proc = Start-Process -FilePath "mise" -ArgumentList "exec", "--", "./gradlew.bat", $task, "--console=plain" `
    -WorkingDirectory $repo -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
$deadline = (Get-Date).AddSeconds(300)
$result = "TIMEOUT"
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 1
    $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
    if ($text -match "Sound engine started") { $result = "STARTED"; break }
    if ($text -match "(?i)crash report|Exception in thread|BUILD FAILED") { $result = "CRASHED"; break }
    if ($proc.HasExited) { $result = "EXITED"; break }
}
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match "devlaunch|DevLaunchInjector|BootstrapLauncher|net\.neoforged\.fml|fabricmc" -and $_.CommandLine -notmatch "GradleDaemon|GradleWrapperMain|redhat\.java" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
"$Loader => $result"
Select-String -Path $log -Pattern "Journeymen|journeymen|ERROR|Exception" | Select-Object -First 15 | ForEach-Object { $_.Line }
