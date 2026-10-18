# Update the website's /vct/ pages with new matches, stopping at the first failure:
#   1. update                 scrape new matches from vlr.gg (stops here if nothing new was saved)
#   2. pipeline.ps1           rebuild vct.db, win probability, WPA
#   3. export_site.py         vct.db -> data/exports/vct_site.sqlite
#   4. publish_site.py        upload it to the GitHub Release, trigger Render's redeploy
#
# Usage, from the project root:
#   .\update_site.ps1               the whole thing
#   .\update_site.ps1 -NoScrape     skip scraping: rebuild from what's saved (e.g. after a rule change)
#   .\update_site.ps1 -PublishOnly  only re-export and upload the current vct.db
#   .\update_site.ps1 -Force        rebuild and publish even if the scrape found nothing new
#   .\update_site.ps1 -IfDue        only if no run has finished in the last 24 hours (what the hourly task uses)
#
# Every run is logged to data\logs\update-<date>.log, and each finished run (including "nothing new")
# records its time in data\last_update.txt. schedule_update.ps1 runs this with -IfDue every hour.
# Needs GITHUB_TOKEN (and optionally RENDER_DEPLOY_HOOK) in .env; see model/publish_site.py.
param(
    [switch]$NoScrape,
    [switch]$PublishOnly,
    [switch]$Force,
    [switch]$IfDue
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$python = ".\.venv\Scripts\python.exe"
$lastRunFile = "data\last_update.txt"

if ($IfDue -and (Test-Path $lastRunFile)) {
    $last = [datetime]::Parse((Get-Content $lastRunFile -Raw).Trim(), [Globalization.CultureInfo]::InvariantCulture)
    if (((Get-Date) - $last).TotalHours -lt 24) { return }  # ran recently: nothing to do, no log
}

New-Item -ItemType Directory -Force data\logs | Out-Null
Start-Transcript -Path ("data\logs\update-{0:yyyy-MM-dd_HHmm}.log" -f (Get-Date)) | Out-Null
try {
    # Saved pages: a new match page or replay page means there's something to rebuild.
    function SavedPages {
        (Get-ChildItem data\raw\matches, data\raw\logs -File -ErrorAction SilentlyContinue | Measure-Object).Count
    }

    if (-not $PublishOnly) {
        if (-not $NoScrape) {
            $before = SavedPages
            Write-Host "`n=== update (scrape new matches) ===" -ForegroundColor Cyan
            .\gradlew.bat run --args="update" --console=plain -q
            if ($LASTEXITCODE -ne 0) { throw "update failed (exit code $LASTEXITCODE)" }
            $new = (SavedPages) - $before
            Write-Host "$new new pages saved"
            if ($new -eq 0 -and -not $Force) {
                Write-Host "`nNothing new; site data unchanged." -ForegroundColor Green
                (Get-Date).ToString("o") | Set-Content $lastRunFile
                return
            }
        }
        .\pipeline.ps1
    }

    Write-Host "`n=== export_site ===" -ForegroundColor Cyan
    & $python model\export_site.py
    if ($LASTEXITCODE -ne 0) { throw "export_site failed (exit code $LASTEXITCODE)" }

    Write-Host "`n=== publish_site ===" -ForegroundColor Cyan
    & $python model\publish_site.py
    if ($LASTEXITCODE -ne 0) { throw "publish_site failed (exit code $LASTEXITCODE)" }

    (Get-Date).ToString("o") | Set-Content $lastRunFile
    Write-Host "`nSite data updated." -ForegroundColor Green
}
finally {
    Stop-Transcript | Out-Null
    # Keep the last 60 logs.
    Get-ChildItem data\logs\update-*.log | Sort-Object Name -Descending | Select-Object -Skip 60 | Remove-Item
}
