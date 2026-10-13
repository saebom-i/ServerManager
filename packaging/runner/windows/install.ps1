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
Write-Host "Installed. Run '$target\pair.cmd' and enter the enrollment code shown in the web UI. The runner starts automatically at logon."
