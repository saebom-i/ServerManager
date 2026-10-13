$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $env:LOCALAPPDATA 'ServerManagerRunner'
New-Item -ItemType Directory -Force -Path $target | Out-Null
if ($source -ne $target) { Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force }
$java = Join-Path $target 'runtime\bin\java.exe'
$bootstrap = Join-Path $target 'runner-bootstrap.jar'
$action = New-ScheduledTaskAction -Execute $java -Argument ('-jar "' + $bootstrap + '"')
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'ServerManagerRunner' -Action $action -Trigger $trigger -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName 'ServerManagerRunner'
Write-Host "Installed. The runner starts automatically at logon."
$runnerHome = if ($env:SERVER_MANAGER_RUNNER_HOME) { $env:SERVER_MANAGER_RUNNER_HOME } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.servermanager-runner' }
if (!(Test-Path (Join-Path $runnerHome 'identity.json')) -and $env:SERVER_MANAGER_SKIP_PAIRING -ne '1') {
    Write-Host 'Register this computer using your management HTTPS address and the code issued after passkey verification.'
    try { & (Join-Path $target 'pair.ps1') } catch { Write-Host "Registration incomplete. Run '$target\pair.cmd' to try again." }
} else { Write-Host "Registration settings: '$target\pair.cmd'" }
