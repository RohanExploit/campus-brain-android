<#
    demo_down.ps1  -  stop the demo stack started by demo_up.ps1.

        powershell -ExecutionPolicy Bypass -File scripts\demo_down.ps1

    Leaves Ollama running (it is a system service and reloading the model
    costs a minute you do not want to pay before the next demo). Pass
    -Ollama to stop it too.
#>
param([switch]$Ollama, [int]$ApiPort = 8000, [int]$WebPort = 3000)

function Stop-Port($port, $label) {
    $pids = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
    if (-not $pids) { Write-Host "[demo] $label already stopped"; return }
    foreach ($id in $pids) {
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        Write-Host "[demo] stopped $label (pid $id)"
    }
}

Stop-Port $WebPort "dashboard"
Stop-Port $ApiPort "API"

# npm.cmd spawns node as a child; killing the listener can orphan it.
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -like '*next*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

if ($Ollama) {
    Get-Process ollama, "ollama app" -ErrorAction SilentlyContinue | Stop-Process -Force
    Write-Host "[demo] stopped Ollama"
}
Write-Host "[demo] down"
