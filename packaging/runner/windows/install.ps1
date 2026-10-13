param([Parameter(ValueFromRemainingArguments=$true)][string[]]$SetupArguments)
$ErrorActionPreference = 'Stop'
$packageHome = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $packageHome 'runtime\bin\java.exe') '-jar' (Join-Path $packageHome 'runner-setup.jar') '--cli' @SetupArguments
exit $LASTEXITCODE
