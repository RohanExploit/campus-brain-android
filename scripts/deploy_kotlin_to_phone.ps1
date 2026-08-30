<#
.SYNOPSIS
    Build, verify and install the native Kotlin Campus Brain app on a connected
    Android device, and optionally hot-swap the corpus without rebuilding.

.DESCRIPTION
    One command from a clean checkout to a working phone. Deliberately fails
    loudly rather than half-succeeding: an APK that installs but opens an empty
    or truncated corpus is a worse demo failure than one that refuses to start.

    Corpus delivery has two paths, matching BrainDb's resolution order:
      * bundled in the APK   (default; survives a factory-reset phone)
      * pushed over adb      (-PushCorpus; swaps the corpus in ~2s, no rebuild)
    The pushed copy is checked FIRST by the app, so -PushCorpus always wins.

.EXAMPLE
    .\scripts\deploy_kotlin_to_phone.ps1
    .\scripts\deploy_kotlin_to_phone.ps1 -PushCorpus
    .\scripts\deploy_kotlin_to_phone.ps1 -Rebuild -Tenant tenant_canon
#>
[CmdletBinding()]
param(
    # Re-export the bundle from the tenant before building.
    [switch]$Rebuild,
    [string]$Tenant = "tenant_canon",
    # Push the corpus to the device's external files dir instead of relying on
    # the APK asset. Fastest way to change data at a venue.
    [switch]$PushCorpus,
    # Skip the Gradle build (install the existing APK).
    [switch]$NoBuild,
    [switch]$Release
)

$ErrorActionPreference = "Stop"

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$AppDir     = Join-Path $RepoRoot "android-app"
$AssetDb    = Join-Path $AppDir "app\src\main\assets\brain.db"
$Package    = "com.kriet.campusbrain"
$RemoteDir  = "/sdcard/Android/data/$Package/files"
$RemoteDb   = "$RemoteDir/brain.db"

# adb: prefer the copy already used by scripts/deploy_to_phone.ps1, then the
# SDK's, then PATH.
$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
           elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
           else { "R:\android-sdk" }
$AdbCandidates = @(
    "R:\android-tools\platform-tools\adb.exe",
    (Join-Path $SdkRoot "platform-tools\adb.exe"),
    "adb"
)
$Adb = $AdbCandidates | Where-Object { $_ -and (Get-Command $_ -ErrorAction SilentlyContinue) } | Select-Object -First 1
if (-not $Adb) { throw "adb not found. Install platform-tools or set ANDROID_HOME." }

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "  [OK]  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  [!]   $msg" -ForegroundColor Yellow }

# --- device ------------------------------------------------------------------
Step "Device"
# @() forces an array: with exactly one device the pipeline yields a bare
# string, and indexing a string gives a char rather than the line.
$devices = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
if (-not $devices) {
    Write-Host @"
  No authorised device.

  On the phone:
    1. Settings > About phone > tap 'Build number' 7 times
    2. Settings > System > Developer options > USB debugging  ON
    3. Reconnect USB, then Allow the 'Allow USB debugging?' prompt

  Then re-run this script.
"@ -ForegroundColor Yellow
    & $Adb devices -l
    throw "no device"
}
$serial = ($devices[0].ToString() -split "\s+")[0]
function Prop($name) { $v = & $Adb -s $serial shell getprop $name; if ($v) { "$v".Trim() } else { "?" } }
$model  = Prop "ro.product.model"
$sdk    = Prop "ro.build.version.sdk"
$abi    = Prop "ro.product.cpu.abi"
Ok "$model  (serial $serial, API $sdk, $abi)"
if ($abi -ne "arm64-v8a") {
    # app/build.gradle.kts restricts abiFilters to arm64-v8a to keep the bundled
    # SQLite payload to one architecture.
    Warn "device ABI is $abi but the APK ships arm64-v8a only; install will fail."
}

# --- corpus ------------------------------------------------------------------
if ($Rebuild) {
    Step "Re-exporting corpus from $Tenant"
    $py = Join-Path $RepoRoot ".venv312\Scripts\python.exe"
    if (-not (Test-Path $py)) { $py = "python" }
    & $py (Join-Path $RepoRoot "scripts\export_mobile_bundle.py") --tenant $Tenant --out $AssetDb
    if ($LASTEXITCODE -ne 0) { throw "export_mobile_bundle failed" }
}

if (-not (Test-Path $AssetDb)) {
    throw "No corpus at $AssetDb. Run with -Rebuild, or copy one in. The app refuses to open an empty database, so building without it produces an APK that cannot answer anything."
}
$dbSize = (Get-Item $AssetDb).Length
Ok ("corpus {0:N2} MB" -f ($dbSize / 1MB))

# --- build -------------------------------------------------------------------
$variant = if ($Release) { "Release" } else { "Debug" }
$apk = Join-Path $AppDir "app\build\outputs\apk\$($variant.ToLower())\app-$($variant.ToLower()).apk"

if (-not $NoBuild) {
    Step "Gradle assemble$variant"
    Push-Location $AppDir
    try {
        # Keep Gradle's cache off C:, which is the small volume on this machine.
        if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "R:\gradle-home" }
        if (-not $env:ANDROID_HOME)     { $env:ANDROID_HOME     = "R:\android-sdk" }
        & .\gradlew.bat ":app:assemble$variant" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
    } finally { Pop-Location }
}
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
Ok ("apk {0:N1} MB" -f ((Get-Item $apk).Length / 1MB))

# --- install -----------------------------------------------------------------
Step "Install"
& $Adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "install failed" }
Ok "installed $Package"

# --- optional corpus push ----------------------------------------------------
if ($PushCorpus) {
    Step "Pushing corpus to device"
    & $Adb -s $serial shell mkdir -p $RemoteDir | Out-Null
    & $Adb -s $serial push $AssetDb $RemoteDb
    if ($LASTEXITCODE -ne 0) { throw "push failed" }

    # Verify the bytes landed intact. A half-written database still OPENS and
    # then returns wrong rows, which is far nastier on stage than one that
    # refuses to open.
    $localHash  = (Get-FileHash -Algorithm MD5 $AssetDb).Hash.ToLower()
    $remoteLine = (& $Adb -s $serial shell md5sum $RemoteDb) 2>$null
    $remoteHash = if ($remoteLine) { ($remoteLine -split "\s+")[0].Trim().ToLower() } else { $null }
    if (-not $remoteHash) {
        Warn "device has no md5sum; falling back to a size comparison"
        $remoteSize = [int64](& $Adb -s $serial shell stat -c %s $RemoteDb).Trim()
        if ($remoteSize -ne $dbSize) { throw "size mismatch: local $dbSize, remote $remoteSize" }
        Ok "size matches ($dbSize bytes)"
    } elseif ($remoteHash -ne $localHash) {
        throw "MD5 mismatch after push. local=$localHash remote=$remoteHash"
    } else {
        Ok "md5 verified $localHash"
    }
}

# --- launch and check --------------------------------------------------------
Step "Launch"
& $Adb -s $serial logcat -c
& $Adb -s $serial shell am force-stop $Package
& $Adb -s $serial shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 6

$log = & $Adb -s $serial logcat -d -s CampusBrain:V AndroidRuntime:E
$ready = $log | Select-String "repository ready"
$crash = $log | Select-String "FATAL EXCEPTION"

if ($crash) {
    Write-Host ($log -join "`n") -ForegroundColor Red
    throw "app crashed on launch"
}
if ($ready) {
    Ok ($ready[0].ToString().Split(":")[-1].Trim())
} else {
    Warn "no 'repository ready' line yet; check: $Adb logcat -s CampusBrain:V"
}

Write-Host @"

Done.

  Corpus source order the app uses:
    1. $RemoteDb        (adb push -- checked first)
    2. internal storage copy
    3. bundled APK asset

  Swap the corpus at a venue, no rebuild:
    .\scripts\deploy_kotlin_to_phone.ps1 -PushCorpus -NoBuild

  Pre-demo check: long-press the 'Campus Brain' title in the app.
  Watch logs:     $Adb logcat -s CampusBrain:V
"@ -ForegroundColor Cyan
