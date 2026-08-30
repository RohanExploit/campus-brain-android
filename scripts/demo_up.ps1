<#
    demo_up.ps1  -  bring the whole Company Brain stack up for a live demo.

        powershell -ExecutionPolicy Bypass -File scripts\demo_up.ps1

    Starts, in order: Ollama, the FastAPI backend, the Next.js dashboard.
    Waits for each to actually answer before starting the next, so a cold
    laptop reaches a demo-ready state in one command instead of three
    terminals and a guess about whether the model finished loading.

    Defaults to loopback. The unauthenticated admin API (/upload, /review,
    /documents) must not be reachable from a conference wifi, so -Lan
    demands an API key rather than silently binding 0.0.0.0.
#>
[CmdletBinding()]
param(
    # Serve on the LAN so a phone can hit the /m client. Requires -ApiKey.
    [switch]$Lan,
    [string]$ApiKey,
    # Skip `next build` and run the dev server instead (faster loop while editing).
    [switch]$Dev,
    [int]$ApiPort = 8000,
    [int]$WebPort = 3000
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Python = Join-Path $Root ".venv312\Scripts\python.exe"
$Logs = Join-Path $Root "debug_outputs"
New-Item -ItemType Directory -Force -Path $Logs | Out-Null

function Say($msg, $color = "Cyan") { Write-Host "[demo] $msg" -ForegroundColor $color }

function Wait-Http($url, $label, $tries = 45, $delay = 4) {
    for ($i = 0; $i -lt $tries; $i++) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 4
            if ($r.StatusCode -eq 200) { Say "$label ready" "Green"; return $true }
        } catch { Start-Sleep -Seconds $delay }
    }
    Say "$label did NOT come up after $($tries * $delay)s" "Red"
    return $false
}

function Stop-Port($port) {
    $pids = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($id in $pids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
}

# --- host binding -----------------------------------------------------------
# 0.0.0.0 exposes /upload and /review to everyone on the network, and those
# endpoints have no auth unless REQUIRE_API_KEY is on. Refuse rather than warn.
$ApiHost = "127.0.0.1"
if ($Lan) {
    if (-not $ApiKey) {
        Say "-Lan needs -ApiKey. Binding 0.0.0.0 without one exposes the admin API (/upload, /review) to the network unauthenticated." "Red"
        exit 1
    }
    $ApiHost = "0.0.0.0"
    $env:REQUIRE_API_KEY = "1"
    $env:API_KEY = $ApiKey
    $env:NEXT_PUBLIC_API_KEY = $ApiKey
}

if (-not (Test-Path $Python)) {
    Say "venv missing at $Python  -  run: python -m venv .venv312; .venv312\Scripts\pip install -r requirements.txt" "Red"
    exit 1
}

# --- 1. Ollama --------------------------------------------------------------
# `ollama list` starts the server as a side effect if it isn't already up.
Say "starting Ollama"
$ollama = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
if (Test-Path $ollama) { & $ollama list | Out-Null } else { Say "ollama.exe not found  -  is Ollama installed?" "Yellow" }

# --- 2. API -----------------------------------------------------------------
Say "starting API on ${ApiHost}:$ApiPort"
Stop-Port $ApiPort
Start-Process -FilePath $Python `
    -ArgumentList "start.py", "--host", $ApiHost, "--port", "$ApiPort" `
    -WorkingDirectory $Root -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $Logs "api_demo.log") `
    -RedirectStandardError  (Join-Path $Logs "api_demo.err")

# First boot downloads/loads MiniLM and warms the 4B model into 4 GB of VRAM.
# That is the slow leg  -  a minute is normal, not a hang.
Say "waiting for API (model warmup can take ~60s on a cold GPU)"
if (-not (Wait-Http "http://127.0.0.1:$ApiPort/health" "API")) {
    Get-Content (Join-Path $Logs "api_demo.err") -Tail 25
    exit 1
}

# --- 3. Dashboard -----------------------------------------------------------
Stop-Port $WebPort
$Dash = Join-Path $Root "dashboard"
if ($Dev) {
    Say "starting dashboard (dev)"
    $npmArgs = @("run", "dev")
} else {
    Say "building dashboard (production)"
    Push-Location $Dash
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { Pop-Location; Say "build failed" "Red"; exit 1 }
    Pop-Location
    $npmArgs = @("run", "start")
}
Start-Process -FilePath "npm.cmd" -ArgumentList $npmArgs `
    -WorkingDirectory $Dash -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $Logs "dash_demo.log") `
    -RedirectStandardError  (Join-Path $Logs "dash_demo.err")

if (-not (Wait-Http "http://localhost:$WebPort/" "Dashboard" 25 2)) {
    Get-Content (Join-Path $Logs "dash_demo.err") -Tail 25
    exit 1
}

# --- 4. Prove it actually answers ------------------------------------------
# A 200 on /health only says the process is alive. Fire one real question so a
# broken index or an unloaded model fails here, on your laptop, and not in the
# room.
Say "smoke-testing a live query"
try {
    $body = @{ query = "What percentage of students failed?"; tenant_id = "tenant_1" } | ConvertTo-Json
    $headers = @{ "Content-Type" = "application/json" }
    if ($ApiKey) { $headers["X-API-Key"] = $ApiKey }
    $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$ApiPort/query" -Method Post -Body $body -Headers $headers -TimeoutSec 120
    Say "route=$($resp.query_type) -> $($resp.answer)" "Green"
} catch {
    Say "smoke query FAILED: $_" "Red"
    exit 1
}

Write-Host ""
Say "DEMO READY" "Green"
Write-Host "  Dashboard : http://localhost:$WebPort"
Write-Host "  Phone view: http://localhost:$WebPort/m"
Write-Host "  API       : http://127.0.0.1:$ApiPort/health"
if ($Lan) {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 |
           Where-Object { $_.InterfaceAlias -like "Wi-Fi*" -and $_.IPAddress -notlike "169.254.*" } |
           Select-Object -First 1).IPAddress
    Write-Host "  On phone  : http://${ip}:$WebPort/m   (same wifi, API key required)"
}
Write-Host ""
Write-Host "  Stop everything: powershell -File scripts\demo_down.ps1"
