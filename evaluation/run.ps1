[CmdletBinding()]
param(
    [switch]$Live,
    [switch]$Preflight,
    [string]$PriceFile,
    [string]$ChromaUrl = 'http://127.0.0.1:18000',
    [string]$Dataset,
    [string]$PythonPath = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backend = Join-Path $project 'backend'
if ($Live -and -not $PriceFile) { throw 'Live requires an explicit -PriceFile pointing to confirmed official prices.' }
if ($Preflight -and -not $Live) { throw '-Preflight requires -Live and uses the same cost ledger.' }
if (-not $Dataset) { $Dataset = Join-Path $PSScriptRoot 'dataset' }
if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf)) { throw 'Pass -PythonPath for an available Python 3 interpreter.' }
$java = (Get-Command java.exe -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$target = Join-Path $backend 'target'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$proof = Join-Path $target 'evaluation-regression.json'
$classpathFile = Join-Path $target 'evaluation-classpath.txt'
$regressionStarted = [DateTime]::UtcNow
$realApiTestUrl = [Environment]::GetEnvironmentVariable('REAL_API_TEST_URL', 'Process')
try {
    # Existing opt-in daily-KB tests must remain disabled even if the parent shell enables them.
    [Environment]::SetEnvironmentVariable('REAL_API_TEST_URL', $null, 'Process')
    Push-Location $backend
    try {
        & $maven '-q' 'test'
        if ($LASTEXITCODE -ne 0) { throw 'Offline Maven regression failed; no live requests started.' }
        $suiteRows = @()
        $testCount = 0; $failureCount = 0; $errorCount = 0; $skipCount = 0
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $target 'surefire-reports') -Filter 'TEST-*.xml') {
            if ($file.LastWriteTimeUtc -lt $regressionStarted.AddSeconds(-2)) { continue }
            [xml]$xml = [IO.File]::ReadAllText($file.FullName)
            $suite = $xml.testsuite
            $testCount += [int]$suite.tests; $failureCount += [int]$suite.failures
            $errorCount += [int]$suite.errors; $skipCount += [int]$suite.skipped
            $suiteRows += @{name=[string]$suite.name; tests=[int]$suite.tests; failures=[int]$suite.failures;
                errors=[int]$suite.errors; skipped=[int]$suite.skipped; sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
        if ($testCount -lt 1 -or $failureCount -ne 0 -or $errorCount -ne 0) { throw 'Fresh regression evidence is missing or failed.' }
        $evidence = @{schemaVersion=1; command='mvn test'; passed=$true; startedAt=$regressionStarted.ToString('o');
            finishedAt=[DateTime]::UtcNow.ToString('o'); tests=$testCount; failures=$failureCount; errors=$errorCount;
            skipped=$skipCount; suites=$suiteRows; realApiTestsDisabled=$true}
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($proof, ($evidence | ConvertTo-Json -Depth 8), $utf8)
        & $maven '-q' '-DincludeScope=test' "-Dmdep.outputFile=$classpathFile" 'dependency:build-classpath'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot build isolated evaluation test classpath.' }
    } finally { Pop-Location }
    & $PythonPath (Join-Path $PSScriptRoot 'report.py') '--self-test'
    if ($LASTEXITCODE -ne 0) { throw 'Offline report tests failed; no live requests started.' }
    $classpath = (Join-Path $target 'test-classes') + [IO.Path]::PathSeparator + (Join-Path $target 'classes') +
        [IO.Path]::PathSeparator + [IO.File]::ReadAllText($classpathFile).Trim()
    $arguments = @('-cp', $classpath, 'local.rag.RagEvaluationMain', '--project-root', $project,
        '--dataset', [IO.Path]::GetFullPath($Dataset), '--regression-evidence', $proof)
    if ($Live) {
        $arguments += @('--live', '--price-file', [IO.Path]::GetFullPath($PriceFile), '--chroma-url', $ChromaUrl)
        if ($Preflight) { $arguments += '--preflight' }
    } else { $arguments += '--offline' }
    $runnerOutput = @(& $java @arguments)
    $runnerExit = $LASTEXITCODE
    $runnerOutput | ForEach-Object { Write-Host $_ }
    $artifactLine = $runnerOutput | Where-Object { $_ -like 'Evaluation artifacts: *' } | Select-Object -Last 1
    if ($artifactLine) {
        $runDirectory = $artifactLine.Substring('Evaluation artifacts: '.Length)
        & $PythonPath (Join-Path $PSScriptRoot 'report.py') '--run' $runDirectory
        $reportExit = $LASTEXITCODE
        if ($reportExit -notin @(0, 1, 2)) { throw 'Report process failed unexpectedly.' }
    }
    if ($runnerExit -ne 0) { throw 'Evaluation incomplete; inspect evaluation/runs/<runid>/run.json for safe diagnostics.' }
    Write-Host 'Runner finished. Offline/awaiting-review metrics are incomplete; pass requires independent review.'
    $global:LASTEXITCODE = 0
} finally {
    [Environment]::SetEnvironmentVariable('REAL_API_TEST_URL', $realApiTestUrl, 'Process')
}
