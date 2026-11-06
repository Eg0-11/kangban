[CmdletBinding()]
param([string]$UvPath)
$ErrorActionPreference = 'Stop'
$localRepoRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $localRepoRoot 'kangban-agent\scripts\initialize.ps1') -UvPath $UvPath
$localPython = Join-Path $localRepoRoot 'kangban-agent\.venv\Scripts\python.exe'
& $localPython (Join-Path $PSScriptRoot 'local_runtime.py') init
if ($LASTEXITCODE -ne 0) { throw 'Local configuration initialization failed.' }
Push-Location -LiteralPath (Join-Path $localRepoRoot 'kangban-web')
try {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
} finally { Pop-Location }
Push-Location -LiteralPath (Join-Path $localRepoRoot 'kangban-server')
try {
    $localBuildTarget = [System.IO.Path]::GetFullPath((Join-Path $PWD.Path 'target'))
    if (-not $localBuildTarget.StartsWith($localRepoRoot + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Unexpected Java build directory.' }
    if ((Test-Path -LiteralPath $localBuildTarget) -and ((Get-Item -LiteralPath $localBuildTarget).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { throw 'The Java build directory must not be a link.' }
    & mvn.cmd --batch-mode clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Java packaging failed.' }
} finally { Pop-Location }
Write-Host 'Initialization complete. Fill kangban.local.env, then run scripts\start-local.ps1.'
