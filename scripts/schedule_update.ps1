# Keep the site updated with Windows Task Scheduler: every hour, run update_site.ps1 -IfDue, which does a full
# update only if none has finished in the last 24 hours (otherwise it exits at once, with no log).
#
#   .\scripts\schedule_update.ps1                 set it up (or update it)
#   .\scripts\schedule_update.ps1 -Remove         stop the automatic updates
#
# The task runs as you, only while you're logged in and the PC is on (it never wakes the PC), in a hidden
# window so nothing pops up.
# Check it: Task Scheduler > Task Scheduler Library > "VCT site update", or the logs in data\logs\.
param(
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
# conhost --headless runs PowerShell without a console window flashing up every hour.
$action = New-ScheduledTaskAction -Execute "conhost.exe" `
    -Argument "--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$script`" -IfDue" `
    -WorkingDirectory (Split-Path $PSScriptRoot -Parent)
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).Date.AddHours((Get-Date).Hour + 1) `
    -RepetitionInterval (New-TimeSpan -Hours 1)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 3) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings `
    -Description "Every hour: update the website's /vct/ data if no update has finished in 24 hours (vct-ratings\scripts\update_site.ps1 -IfDue)" `
    -Force | Out-Null
Write-Host "Scheduled '$name': hourly check, full update when none has finished in 24 hours. Logs go to data\logs\."
