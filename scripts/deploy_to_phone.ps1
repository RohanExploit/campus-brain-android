<#
    deploy_to_phone.ps1  -  put the standalone app on the handset, end to end.

        powershell -ExecutionPolicy Bypass -File scripts\deploy_to_phone.ps1

    Downloads the newest APK that CI built, installs it, pushes the corpus and
    the model into the app's own directory, verifies both by checksum, and
    launches the app.

    There is no Android toolchain on this machine -- no Flutter SDK, no Android
    SDK, no Gradle -- so CI is the compiler and this script is the installer.
    The only local Android tool needed is adb.

    Neither payload is in git: brain.db carries real student names and the
    repository is public, and the model is 1.9 GB. Both live on disk and are
    delivered over USB, which also means the venue network is never in the path.
#>
[CmdletBinding()]
param(
    # Skip the CI download and install whatever APK is already at -ApkPath.
    [switch]$SkipDownload,
    [string]$ApkPath,
    [string]$Adb   = "R:\android-tools\platform-tools\adb.exe",
    [string]$Db    = "R:\Startup research\Start up V2\mobile\assets\brain.db",
    [string]$Model = "R:\models\gemma-4-E2B-it-gpu.litertlm",
    # Corpus only; skip the 1.9 GB model push when it is already on the device.
    [switch]$NoModel
)

$ErrorActionPreference = "Stop"
$Pkg     = "com.companybrain.company_brain"
$Remote  = "/sdcard/Android/data/$Pkg/files"
$Root    = Split-Path -Parent $PSScriptRoot
$Staging = Join-Path $env:TEMP "cb-apk"

function Say($m, $c = "Cyan") { Write-Host "[deploy] $m" -ForegroundColor $c }

function Assert-Match($localPath, $remotePath, $label) {
    $local  = (Get-FileHash $localPath -Algorithm MD5).Hash.ToLower()
    $remote = (& $Adb shell "md5sum $remotePath" 2>$null).Split(" ")[0].Trim()
    if ($local -ne $remote) {
        Say "$label CHECKSUM MISMATCH  -  local $local vs device $remote" "Red"
        Say "The push did not land intact. Do not demo this build." "Red"
        exit 1
    }
    Say "$label verified ($local)" "Green"
}

# --- adb + device -----------------------------------------------------------
if (-not (Test-Path $Adb)) { Say "adb not found at $Adb" "Red"; exit 1 }

$devices = & $Adb devices | Select-String -Pattern "\sdevice$"
if (-not $devices) {
    Say "No device. Check the cable, and that USB debugging is on and this computer is authorised." "Red"
    & $Adb devices
    exit 1
}
Say "device: $((& $Adb shell 'getprop ro.product.model').Trim())"

# --- APK --------------------------------------------------------------------
if (-not $SkipDownload) {
    Say "fetching the newest APK CI built"
    Remove-Item $Staging -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $Staging | Out-Null
    # Newest *successful* Android run -- a red run's artifact is not installable.
    $runId = (gh run list --workflow Android --status success --limit 1 --json databaseId --jq '.[0].databaseId')
    if (-not $runId) { Say "No successful Android run yet. Push mobile/ and wait for CI." "Red"; exit 1 }
    Say "run $runId"
    gh run download $runId --dir $Staging
    $ApkPath = (Get-ChildItem $Staging -Recurse -Filter *.apk | Select-Object -First 1).FullName
}
if (-not $ApkPath -or -not (Test-Path $ApkPath)) { Say "No APK to install." "Red"; exit 1 }
Say "installing $(Split-Path $ApkPath -Leaf) ($([math]::Round((Get-Item $ApkPath).Length/1MB,1)) MB)"
& $Adb install -r $ApkPath

# --- payloads ---------------------------------------------------------------
# The app's external dir is created by Android on first launch, so create it
# explicitly -- a fresh install has not run yet and the push would fail.
& $Adb shell "mkdir -p $Remote" 2>$null | Out-Null

if (-not (Test-Path $Db)) {
    Say "brain.db missing. Build it: python scripts\export_mobile_bundle.py" "Red"; exit 1
}
Say "pushing corpus ($([math]::Round((Get-Item $Db).Length/1MB,2)) MB)"
& $Adb push $Db "$Remote/brain.db"
Assert-Match $Db "$Remote/brain.db" "corpus"

if (-not $NoModel) {
    if (-not (Test-Path $Model)) {
        Say "model not found at $Model  -  skipping. Retrieval will work; generation will not." "Yellow"
    } else {
        $sizeGb = [math]::Round((Get-Item $Model).Length/1GB, 2)
        Say "pushing model ($sizeGb GB)  -  this takes a minute over USB"
        & $Adb push $Model "$Remote/$(Split-Path $Model -Leaf)"
        Assert-Match $Model "$Remote/$(Split-Path $Model -Leaf)" "model"
    }
}

# --- launch -----------------------------------------------------------------
Say "launching"
& $Adb shell "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" 2>$null | Out-Null

Write-Host ""
Say "READY" "Green"
& $Adb shell "ls -lah $Remote"
Write-Host ""
Write-Host "  Logs:  $Adb logcat -s flutter"
Write-Host "  Again, corpus only:  powershell -File scripts\deploy_to_phone.ps1 -NoModel"
