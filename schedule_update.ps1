# Run update_site.ps1 automatically every day with Windows Task Scheduler.
#
#   .\schedule_update.ps1                 daily at 05:00
#   .\schedule_update.ps1 -At 14:30       daily at another time
#   .\schedule_update.ps1 -Remove         stop the automatic updates
#
# The task runs as you, only while you're logged in, and catches up when the PC wakes if it missed a run.
# Check it: Task Scheduler > Task Scheduler Library > "VCT site update", or the logs in data\logs\.
param(
    [string]$At = "05:00",
    [switch]$Remove
)
$ErrorActionPreference = "Stop"
$name = "VCT site update"

if ($Remove) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false
    Write-Host "Removed '$name'."
    return
}

$script = Join-Path $PSScriptRoot "update_site.ps1"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`"" -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 3) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings `
    -Description "Scrape new VCT matches and update the website's /vct/ data (vct-ratings\update_site.ps1)" `
    -Force | Out-Null
Write-Host "Scheduled '$name' daily at $At. Logs go to data\logs\."
