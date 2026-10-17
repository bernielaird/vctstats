# Rebuild everything downstream of the saved HTML, in order, stopping at the first failure:
#   1. build-db       saved pages -> data/vct.db             (Java, ~2-5 min)
#   2. train_wpa.py   win probability for every game state    (Python, ~30 s)
#   3. event_wpa.py   credit for every kill, per player       (Python, ~1 min)
#
# Usage, from the project root:
#   .\pipeline.ps1              rebuild everything
#   .\pipeline.ps1 -Update      first fetch new matches from vlr.gg (needs .env), then rebuild
#   .\pipeline.ps1 -SkipBuild   only rerun the Python steps (e.g. after changing a WPA rule)
#
# Close anything that has data\vct.db open first (DB Browser, SQLite Viewer): Windows locks it.
param(
    [switch]$Update,
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$python = ".\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    throw "No .venv found. Set it up once: python -m venv .venv; .venv\Scripts\python -m pip install -r model\requirements.txt"
}

function Step($name, [scriptblock]$block) {
    Write-Host "`n=== $name ===" -ForegroundColor Cyan
    $started = Get-Date
    & $block
    if ($LASTEXITCODE -ne 0) { throw "$name failed (exit code $LASTEXITCODE)" }
    Write-Host ("--- {0} done in {1:n0}s" -f $name, ((Get-Date) - $started).TotalSeconds) -ForegroundColor DarkGray
}

if ($Update) {
    Step "update (scrape new matches)" { .\gradlew.bat run --args="update" --console=plain -q }
}
if (-not $SkipBuild) {
    Step "build-db" { .\gradlew.bat run --args="build-db" --console=plain -q }
}
Step "train_wpa" { & $python model\train_wpa.py }
Step "event_wpa" { & $python model\event_wpa.py }

Write-Host "`nAll done. Reports: $python model\report.py --season 2026" -ForegroundColor Green
