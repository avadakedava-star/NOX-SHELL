param([Parameter(Mandatory = $true)][string]$NodePath)

$ErrorActionPreference = 'Stop'
$taskClientRoot = Split-Path -Parent $PSScriptRoot
$taskRoot = Split-Path -Parent $taskClientRoot
$taskFrontendScript = Join-Path $PSScriptRoot 'start-background-dev.mjs'
$taskFrontend = Start-Process -FilePath $NodePath `
    -ArgumentList @('"' + $taskFrontendScript + '"') `
    -WorkingDirectory $taskClientRoot -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $taskRoot 'client-bootstrap.log') `
    -RedirectStandardError (Join-Path $taskRoot 'client-bootstrap.err.log') -PassThru
$taskFrontend.Id | Set-Content -LiteralPath (Join-Path $taskRoot 'client.pid')
$taskFrontend.WaitForExit()
exit $taskFrontend.ExitCode
