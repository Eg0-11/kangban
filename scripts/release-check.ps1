[CmdletBinding()]
param([string]$UvPath, [string]$MavenRepository, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$releaseRoot = Split-Path -Parent $PSScriptRoot
function Invoke-ReleaseCheck([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "A release check failed with exit code $LASTEXITCODE." }
}
if (-not $UvPath) {
    $releaseUv = Get-Command uv -CommandType Application -ErrorAction SilentlyContinue
    $UvPath = if ($releaseUv) { $releaseUv.Source } else { Join-Path $env:USERPROFILE '.local\bin\uv.exe' }
}
Push-Location -LiteralPath $releaseRoot
$releasePreviousLive = $env:KANGBAN_RUN_LIVE_RAG
try {
    Write-Host '[1/7] Workspace format and ignored local configuration'
    Invoke-ReleaseCheck git @('diff', '--check')
    Invoke-ReleaseCheck git @('diff', '--cached', '--check')
    foreach ($releaseSecretFile in @('kangban-server/.env', 'kangban-web/.env.local', 'kangban-agent/.env', 'kangban.local.env')) {
        Invoke-ReleaseCheck git @('check-ignore', '-q', $releaseSecretFile)
    }
    Write-Host '[2/7] Existing release secret format scan'
    $releasePattern = ('s'+'k-')+'[A-Za-z0-9_-]{20,}|'+('AI'+'za')+'[A-Za-z0-9_-]{20,}|'+('AK'+'IA')+'[A-Z0-9]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
    $releaseCurrentHits = & git grep -Il -E $releasePattern -- . ':(exclude)agent/**'
    if ($LASTEXITCODE -gt 1) { throw 'The current-version scan could not run.' }
    if ($releaseCurrentHits) { throw 'The existing release scan found a credential-shaped value.' }
    $releaseHistoryHits = & git log --all --format=%H "-G$releasePattern" -- . ':(exclude)agent/**'
    if ($LASTEXITCODE -ne 0) { throw 'The Git history scan could not run.' }
    if ($releaseHistoryHits) { throw 'The existing release scan found a credential-shaped value in history.' }
    Write-Host '[3/7] Locked Python environment and tests'
    $releaseUvArguments = @('sync', '--project', 'kangban-agent', '--locked', '--python', '3.12.14')
    if ($Offline) { $releaseUvArguments += '--offline' }
    Invoke-ReleaseCheck $UvPath $releaseUvArguments
    Push-Location kangban-agent
    try { Invoke-ReleaseCheck '.\.venv\Scripts\python.exe' @('-m', 'pytest', '-q') } finally { Pop-Location }
    Write-Host '[4/7] Frontend tests'
    Push-Location kangban-web
    try {
        Invoke-ReleaseCheck npm.cmd @('test')
        Write-Host '[5/7] Frontend production build'
        Invoke-ReleaseCheck npm.cmd @('run', 'build')
    } finally { Pop-Location }
    Write-Host '[6/7] Clean Java build and local Python integration'
    $env:KANGBAN_RUN_LIVE_RAG = 'false'
    $releaseMavenArguments = @('--batch-mode', 'clean', 'verify', '-Dspring.profiles.active=test', '-Dapp.ai.provider=mock',
        '-Dapp.agent.rag.embedding-provider=local', '-Dapp.mcp.public.enabled=false', '-Dspring.ai.mcp.client.enabled=false',
        '-Dspring.datasource.url=jdbc:h2:mem:kangban_test;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE',
        '-Dspring.datasource.driver-class-name=org.h2.Driver', '-Dspring.datasource.username=sa', '-Dspring.datasource.password=')
    if ($Offline) { $releaseMavenArguments += '--offline' }
    if ($MavenRepository) { $releaseMavenArguments += "-Dmaven.repo.local=$MavenRepository" }
    Push-Location kangban-server
    try {
        $releaseBuildTarget = [System.IO.Path]::GetFullPath((Join-Path $PWD.Path 'target'))
        if (-not $releaseBuildTarget.StartsWith($releaseRoot + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Unexpected Java build directory.' }
        if ((Test-Path -LiteralPath $releaseBuildTarget) -and ((Get-Item -LiteralPath $releaseBuildTarget).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { throw 'The Java build directory must not be a link.' }
        Invoke-ReleaseCheck mvn.cmd $releaseMavenArguments
    } finally { Pop-Location }
    Write-Host '[7/7] Actual Java callbacks and strict Python types'
    Push-Location kangban-agent
    try { Invoke-ReleaseCheck '.\.venv\Scripts\python.exe' @('scripts/check-java-contract.py') } finally { Pop-Location }
    Write-Host 'Release checks passed. No real model, MySQL or MCP was called.'
} finally {
    if ($null -eq $releasePreviousLive) { Remove-Item Env:KANGBAN_RUN_LIVE_RAG -ErrorAction SilentlyContinue }
    else { $env:KANGBAN_RUN_LIVE_RAG = $releasePreviousLive }
    Pop-Location
}
