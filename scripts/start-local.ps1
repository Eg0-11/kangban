[CmdletBinding()]
param(
    [string]$ConfigurationPath,
    [ValidateRange(1, 65535)][int]$JavaPort = 8080,
    [ValidateRange(1, 65535)][int]$AgentPort = 8093,
    [ValidateRange(1, 65535)][int]$WebPort = 5173,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$localRepoRoot = Split-Path -Parent $PSScriptRoot
$localPython = Join-Path $localRepoRoot 'kangban-agent\.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $localPython -PathType Leaf)) { throw 'Run scripts\initialize-local.ps1 first.' }
$localArguments = @((Join-Path $PSScriptRoot 'local_runtime.py'), 'start', '--java-port', "$JavaPort", '--agent-port', "$AgentPort", '--web-port', "$WebPort")
if ($ConfigurationPath) { $localArguments += @('--config', $ConfigurationPath) }
if ($Check) { $localArguments += '--check' }
& $localPython @localArguments
if ($LASTEXITCODE -ne 0) { throw "Local startup exited with code $LASTEXITCODE." }
