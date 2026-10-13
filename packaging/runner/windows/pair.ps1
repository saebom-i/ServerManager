$ErrorActionPreference = 'Stop'
$packageHome = Split-Path -Parent $MyInvocation.MyCommand.Path
$managerUrl = (Read-Host 'Management HTTPS URL (example: https://mc.example.com)').Trim().TrimEnd('/')
$uri = [Uri]$managerUrl
if (!$uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
    throw 'Enter the management HTTPS address without a path, user credentials, query, or fragment.'
}
Write-Host 'In the web UI, open Server runners and issue a registration code with your passkey.'
Start-Process ($managerUrl + '/#runners')
& (Join-Path $packageHome 'runtime\bin\java.exe') '-jar' (Join-Path $packageHome 'server-manager.jar') '--runner-pair' ('--manager-url=' + $managerUrl)
if ($LASTEXITCODE -ne 0) { throw 'Registration failed. Check the HTTPS address and issue a new registration code.' }
