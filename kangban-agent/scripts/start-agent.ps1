[CmdletBinding()]
param(
    [string]$ListenAddress = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$ListenPort = 8093,
    [ValidateSet('critical', 'error', 'warning', 'info', 'debug', 'trace')]
    [string]$LogLevel = 'info'
)

$ErrorActionPreference = 'Stop'
$agentProjectRoot = Split-Path -Parent $PSScriptRoot
$agentPythonExecutable = Join-Path $agentProjectRoot '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $agentPythonExecutable -PathType Leaf)) {
    throw 'The project environment is missing. Run scripts\initialize.ps1 first.'
}

Push-Location -LiteralPath $agentProjectRoot
try {
    & $agentPythonExecutable -m uvicorn kangban_agent.main:create_app --factory --app-dir src `
        --host $ListenAddress --port $ListenPort --log-level $LogLevel --no-access-log
    if ($LASTEXITCODE -ne 0) {
        throw "The Agent service exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
