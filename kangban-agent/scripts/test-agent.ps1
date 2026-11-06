[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$agentProjectRoot = Split-Path -Parent $PSScriptRoot
$agentPythonExecutable = Join-Path $agentProjectRoot '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $agentPythonExecutable -PathType Leaf)) {
    throw 'The project environment is missing. Run scripts\initialize.ps1 first.'
}

Push-Location -LiteralPath $agentProjectRoot
try {
    & $agentPythonExecutable -m pytest -q
    if ($LASTEXITCODE -ne 0) {
        throw "Agent checks failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
