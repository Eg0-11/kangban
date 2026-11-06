[CmdletBinding()]
param(
    [string]$UvPath
)

$ErrorActionPreference = 'Stop'
$agentProjectRoot = Split-Path -Parent $PSScriptRoot

if ($UvPath) {
    $agentUvExecutable = (Resolve-Path -LiteralPath $UvPath).Path
} else {
    $agentUvCommand = Get-Command uv -CommandType Application -ErrorAction SilentlyContinue
    if ($agentUvCommand) {
        $agentUvExecutable = $agentUvCommand.Source
    } else {
        $agentUvExecutable = Join-Path $env:USERPROFILE '.local\bin\uv.exe'
    }
}

if (-not (Test-Path -LiteralPath $agentUvExecutable -PathType Leaf)) {
    throw 'uv was not found. Install uv from https://docs.astral.sh/uv/getting-started/installation/ or pass -UvPath.'
}

$agentPreviousEnvironment = $env:UV_PROJECT_ENVIRONMENT
$env:UV_PROJECT_ENVIRONMENT = Join-Path $agentProjectRoot '.venv'
try {
    & $agentUvExecutable sync --project $agentProjectRoot --locked --python 3.12.14
    if ($LASTEXITCODE -ne 0) {
        throw "Dependency synchronization failed with exit code $LASTEXITCODE."
    }

    $agentPythonExecutable = Join-Path $agentProjectRoot '.venv\Scripts\python.exe'
    & $agentUvExecutable pip check --python $agentPythonExecutable
    if ($LASTEXITCODE -ne 0) {
        throw "Dependency compatibility check failed with exit code $LASTEXITCODE."
    }

    & $agentPythonExecutable -c 'import sys; print("Python " + sys.version.split()[0]); print(sys.executable)'
    if ($LASTEXITCODE -ne 0) {
        throw 'The project Python interpreter could not be started.'
    }
} finally {
    if ($null -eq $agentPreviousEnvironment) {
        Remove-Item Env:UV_PROJECT_ENVIRONMENT -ErrorAction SilentlyContinue
    } else {
        $env:UV_PROJECT_ENVIRONMENT = $agentPreviousEnvironment
    }
}
