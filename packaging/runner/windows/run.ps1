$ErrorActionPreference = 'Stop'
$packageHome = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:RUNNER_PACKAGE_HOME = $packageHome
$runnerHome = if ($env:SERVER_MANAGER_RUNNER_HOME) { $env:SERVER_MANAGER_RUNNER_HOME } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.servermanager-runner' }
if (!(Test-Path (Join-Path $runnerHome 'identity.json')) -and $env:SERVER_MANAGER_SKIP_PAIRING -ne '1') {
    Write-Host 'Register this computer before running Minecraft servers.'
    & (Join-Path $packageHome 'pair.ps1')
}
& (Join-Path $packageHome 'runtime\bin\java.exe') '-jar' (Join-Path $packageHome 'runner-bootstrap.jar')
