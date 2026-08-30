<#
    claim.ps1  -  tell the other machines what this one is doing.

        powershell -File scripts\claim.ps1 -Lane mobile -Task "Android Task 2  -  Answer model"
        powershell -File scripts\claim.ps1 -Release
        powershell -File scripts\claim.ps1 -Show

    Writes .claims/<device>.md, commits ONLY that file, rebases, and pushes.

    One file per device on purpose. A single shared "who is doing what" file
    would conflict every time two machines updated it at once  -  which is
    precisely the situation it exists to prevent. Separate files never conflict,
    so this can never be the thing that breaks your push.
#>
[CmdletBinding()]
param(
    [string]$Lane,
    [string]$Task,
    [string]$Branch,
    [switch]$Release,
    [switch]$Show
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Device = if ($env:CLAIM_DEVICE) { $env:CLAIM_DEVICE } else { $env:COMPUTERNAME }
$Dir = Join-Path $Root ".claims"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$File = Join-Path $Dir "$Device.md"

function Show-Claims {
    Write-Host ""
    Write-Host "=== who is working on what ===" -ForegroundColor Cyan
    git -C $Root fetch origin --quiet 2>$null
    git -C $Root checkout origin/main -- .claims 2>$null | Out-Null
    $files = Get-ChildItem $Dir -Filter *.md -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -ne "README.md" }
    if (-not $files) { Write-Host "  (nobody has claimed anything yet)"; return }
    foreach ($f in $files) {
        Write-Host ""
        Get-Content $f.FullName | ForEach-Object { Write-Host "  $_" }
    }
    Write-Host ""
    Write-Host "=== last 5 pushes ===" -ForegroundColor Cyan
    git -C $Root log origin/main --format="  %h  %an  %ar  %s" -5
}

if ($Show) { Show-Claims; exit 0 }

$stamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mmZ")
if (-not $Branch) { $Branch = (git -C $Root branch --show-current) }

if ($Release) {
    $body = @"
# $Device

- **status:** idle
- **updated:** $stamp

Nothing claimed. Any lane is free as far as this machine is concerned.
"@
    $msg = "chore(claims): $Device released its lane"
} else {
    if (-not $Lane) { Write-Host "Need -Lane (mobile | retrieval | dashboard | eval | docs), or -Release" -ForegroundColor Red; exit 1 }
    $body = @"
# $Device

- **status:** working
- **lane:** $Lane
- **branch:** $Branch
- **task:** $Task
- **updated:** $stamp
"@
    $msg = "chore(claims): $Device is on $Lane"
}

Set-Content -Path $File -Value $body -Encoding utf8

# Commit only this file, then rebase before pushing so a claim can never be the
# thing that causes a conflict or clobbers someone's work.
git -C $Root add ".claims/$Device.md"
git -C $Root commit -m $msg --quiet
git -C $Root pull --rebase origin main --quiet
git -C $Root push origin HEAD --quiet

Write-Host "claimed: $Device -> $(if ($Release) { 'idle' } else { "$Lane / $Task" })" -ForegroundColor Green
Show-Claims
