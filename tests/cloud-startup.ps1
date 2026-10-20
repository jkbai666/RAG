# Offline regression: execute copied entrypoints with synthetic files and command stubs.
# No database, installer, build tool, local model or cloud API is actually started.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$originalEnvironment = [Environment]::GetEnvironmentVariables('Process')
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempParent ('rag-cloud-startup-' + [Guid]::NewGuid().ToString('N'))
$checks = 0
function Assert-CloudTest {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Cloud startup regression: $Message" }
    $script:checks++
}
function Assert-Sequence {
    param([object[]]$Actual, [object[]]$Expected, [string]$Message)
    Assert-CloudTest (($Actual -join '|') -ceq ($Expected -join '|')) $Message
}
function Reset-TestState {
    $global:CloudStartupTestState = @{
        Native = New-Object 'System.Collections.Generic.List[string]'
        Processes = New-Object 'System.Collections.Generic.List[string]'
        Waits = New-Object 'System.Collections.Generic.List[string]'
        BackendEnvironmentClear = $false
    }
}
try {
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts') -Force | Out-Null
    foreach ($relative in @('scripts/Common.ps1','scripts/setup.ps1','scripts/start.ps1','tests/cloud-startup.ps1')) {
        $tokens = $null; $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot $relative), [ref]$tokens, [ref]$errors)
        Assert-CloudTest ($errors.Count -eq 0) "PowerShell parse failed: $relative"
        if ($relative -like 'scripts/*') {
            Copy-Item -LiteralPath (Join-Path $projectRoot $relative) -Destination (Join-Path $testRoot $relative)
        }
    }
    Copy-Item -LiteralPath (Join-Path $projectRoot '.env.example') -Destination (Join-Path $testRoot '.env.example')
    # Dot-source only the temporary copy; never load the real private app.env.
    . (Join-Path $testRoot 'scripts/Common.ps1')
    $envPath = Join-Path $script:LocalRoot 'app.env'
    $fixture = @'
# Synthetic credentials; the real private file is never read.
DB_URL=jdbc:mysql://127.0.0.1:13306/fixture?one=1&two=2
DB_USERNAME=fixture_user
DB_PASSWORD=fixture=db=password
DB_ROOT_PASSWORD=fixture-root-password
CHROMA_BASE_URL=http://127.0.0.1:18000/
CHROMA_TIMEOUT_SECONDS=7
PORT=8088
CHAT_BASE_URL=http://127.0.0.1:8091/v1
CHAT_API_KEY=local-not-a-secret
EMBEDDING_MODEL=local-embedding
CHUNK_SIZE=300
CHUNK_OVERLAP=50
CHROMA_COLLECTION=rag_mvp
MODEL_TIMEOUT_SECONDS=5
RETRIEVAL_MIN_SCORE=0.1
RAG_CHATAPIKEY=local-not-a-secret
RAG_EMBEDDING_DIMENSIONS=512
SPRING_APPLICATION_JSON={"rag":{"chat-api-key":"local-not-a-secret"}}
UNRELATED_FILE_SETTING=must-not-be-imported
'@
    [IO.File]::WriteAllText($envPath, $fixture)
    $legacyNames = @('CHAT_BASE_URL','CHAT_API_KEY','CHAT_MODEL','CHAT_MAX_OUTPUT_TOKENS',
        'EMBEDDING_BASE_URL','EMBEDDING_API_KEY','EMBEDDING_MODEL','EMBEDDING_DIMENSIONS',
        'CHUNK_SIZE','CHUNK_OVERLAP','CHROMA_COLLECTION','MODEL_TIMEOUT_SECONDS','RETRIEVAL_MIN_SCORE',
        'RAG_CHATAPIKEY','RAG_CHAT_API_KEY','RAG_EMBEDDINGDIMENSIONS','RAG_EMBEDDING_DIMENSIONS',
        'RAG_CHUNKSIZE','RAG_CHUNK_SIZE','RAG_CHROMACOLLECTION','RAG_CHROMA_COLLECTION',
        'RAG_TIMEOUTSECONDS','RAG_TIMEOUT_SECONDS','RAG_MINSCORE','RAG_MIN_SCORE')
    foreach ($name in $legacyNames) { [Environment]::SetEnvironmentVariable($name, 'inherited-legacy-fixture', 'Process') }
    [Environment]::SetEnvironmentVariable('UNRELATED_FILE_SETTING', $null, 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON', $null, 'Process')
    $env:CLOUD_STARTUP_UNRELATED = 'inherited-value-preserved'
    Import-AppEnvironment
    foreach ($name in $legacyNames) {
        Assert-CloudTest (-not [Environment]::GetEnvironmentVariables('Process').Contains($name)) "Inherited model override was retained: $name"
    }
    Assert-CloudTest ($env:DB_URL -ceq 'jdbc:mysql://127.0.0.1:13306/fixture?one=1&two=2') 'DB URL parsing lost equals signs'
    Assert-CloudTest ($env:DB_PASSWORD -ceq 'fixture=db=password') 'DB password parsing lost equals signs'
    Assert-CloudTest ($env:DB_ROOT_PASSWORD -ceq 'fixture-root-password') 'Root credential was not imported'
    Assert-CloudTest ($env:DB_USERNAME -ceq 'fixture_user') 'DB user was not imported'
    Assert-CloudTest ($env:CHROMA_BASE_URL -ceq 'http://127.0.0.1:18000/') 'Chroma address was not imported'
    Assert-CloudTest ($env:CHROMA_TIMEOUT_SECONDS -ceq '7') 'Chroma timeout was not imported'
    Assert-CloudTest ($env:PORT -ceq '8088') 'Infrastructure port was not imported'
    Assert-CloudTest ([string]::IsNullOrEmpty($env:UNRELATED_FILE_SETTING)) 'Non-allowlisted setting was imported'
    Assert-CloudTest ([string]::IsNullOrEmpty($env:SPRING_APPLICATION_JSON)) 'Arbitrary Spring override was imported'
    Assert-CloudTest ($env:CLOUD_STARTUP_UNRELATED -ceq 'inherited-value-preserved') 'Unrelated parent environment was cleared'
    Assert-CloudTest ($env:ANONYMIZED_TELEMETRY -ceq 'False') 'Chroma telemetry override missing'
    Assert-CloudTest ($env:PYTHONIOENCODING -ceq 'utf-8') 'Python encoding override missing'
    Assert-CloudTest ([IO.File]::ReadAllText($envPath) -ceq $fixture) 'Import changed app.env'
    Import-AppEnvironment
    Assert-CloudTest ([string]::IsNullOrEmpty($env:CHAT_API_KEY)) 'Environment import is not idempotent'

    # Native paths are aliases scoped to the copied entrypoint. Fail closed on
    # unexpected commands instead of invoking real tools or services.
    $stubs = @'
function Get-PythonExecutable { param([string]$Requested); return 'cloud-test-python' }
function Get-Command { param([string]$Name); return [pscustomobject]@{ Source = $Name } }
function Invoke-CloudTestNative {
    param([string]$StubExecutableName)
    $call = $null
    if ($StubExecutableName -eq 'cloud-test-python' -and $args[0] -eq '-c') {
        $call = 'python-version'
    } elseif ($StubExecutableName -eq 'npm.cmd' -and $args[0] -eq 'ci') {
        $call = 'npm-ci'
    } elseif ($StubExecutableName -eq 'npm.cmd' -and ($args -join ' ') -eq 'run build') {
        $call = 'npm-build'
    } elseif ($StubExecutableName -eq 'mvn.cmd' -and $args[0] -eq '-f' -and $args[2] -eq 'package') {
        $call = 'mvn-package'
    } elseif ($StubExecutableName -eq (Join-Path $script:LocalRoot 'chroma-venv\Scripts\python.exe') -and
        ($args -join ' ') -eq '-m pip install --disable-pip-version-check chromadb==1.5.9') {
        $call = 'pip-chroma'
    } elseif ($StubExecutableName -eq (Join-Path $script:LocalRoot 'test-mysql-bin\mysqladmin.exe') -and $args[-1] -eq 'ping') {
        $call = 'mysql-ping'
    } else { throw 'Unexpected native command in isolated startup regression.' }
    $global:CloudStartupTestState.Native.Add($call)
    $global:LASTEXITCODE = 0
    if ($call -eq 'python-version') { return '3.12' }
}
function Invoke-TestPython { Invoke-CloudTestNative 'cloud-test-python' @args }
function Invoke-TestPip { Invoke-CloudTestNative (Join-Path $script:LocalRoot 'chroma-venv\Scripts\python.exe') @args }
function Invoke-TestNpm { Invoke-CloudTestNative 'npm.cmd' @args }
function Invoke-TestMvn { Invoke-CloudTestNative 'mvn.cmd' @args }
function Invoke-TestMySqlAdmin { Invoke-CloudTestNative (Join-Path $script:LocalRoot 'test-mysql-bin\mysqladmin.exe') @args }
Set-Alias -Name 'cloud-test-python' -Value Invoke-TestPython
Set-Alias -Name 'npm.cmd' -Value Invoke-TestNpm
Set-Alias -Name 'mvn.cmd' -Value Invoke-TestMvn
Set-Alias -Name (Join-Path $script:LocalRoot 'chroma-venv\Scripts\python.exe') -Value Invoke-TestPip
Set-Alias -Name (Join-Path $script:LocalRoot 'test-mysql-bin\mysqladmin.exe') -Value Invoke-TestMySqlAdmin
function Start-ManagedProcess {
    param([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Marker, [int]$Port)
    if ($Name -notin @('mysql','chroma','backend','chat-web','docs-web')) { throw 'Unexpected process in cloud startup.' }
    $global:CloudStartupTestState.Processes.Add($Name)
    if ($Name -eq 'backend') {
        $remaining = @([Environment]::GetEnvironmentVariables('Process').Keys | Where-Object {
            $_ -match '^(CHAT_|EMBEDDING_|CHUNK_|MODEL_|RETRIEVAL_)' -or $_ -eq 'CHROMA_COLLECTION' -or
            $_ -match '^RAG_(CHAT|EMBEDDING|CHUNK|CHROMA_?COLLECTION|TIMEOUT_?SECONDS|MIN_?SCORE)'
        })
        if ($remaining.Count -gt 0) { throw 'Model overrides reached the backend process.' }
        if ($env:DB_PASSWORD -cne 'fixture=db=password') { throw 'Infrastructure credentials missing from backend environment.' }
        $global:CloudStartupTestState.BackendEnvironmentClear = $true
    }
}
function Wait-Http {
    param([string]$Url, [int]$Seconds = 90)
    if ($Url -notin @('http://127.0.0.1:18000/api/v2/heartbeat','http://127.0.0.1:8088/api/health',
        'http://127.0.0.1:5173','http://127.0.0.1:5174')) { throw 'Unexpected network wait in cloud startup.' }
    $global:CloudStartupTestState.Waits.Add($Url)
}
'@
    Add-Content -LiteralPath (Join-Path $testRoot 'scripts/Common.ps1') -Value $stubs -Encoding UTF8
    $mysqlBin = Join-Path $script:LocalRoot 'test-mysql-bin'
    foreach ($directory in @('mysql/data/mysql','chroma-venv/Scripts','test-mysql-bin','frontend-chat','frontend-docs')) {
        $directoryRoot = if ($directory -like 'frontend-*') { $testRoot } else { $script:LocalRoot }
        New-Item -ItemType Directory -Path (Join-Path $directoryRoot $directory) -Force | Out-Null
    }
    foreach ($file in @('mysql/my.ini','mysql/admin.cnf','chroma-venv/Scripts/python.exe','test-mysql-bin/mysqld.exe')) {
        [IO.File]::WriteAllText((Join-Path $script:LocalRoot $file), 'fixture-sentinel')
    }
    foreach ($legacySwitch in @($false, $true)) {
        Reset-TestState
        $parameters = @{ MySqlBin = $mysqlBin }
        if ($legacySwitch) { $parameters.SkipModels = $true; $parameters.DownloadProxy = 'http://fixture.invalid' }
        & (Join-Path $testRoot 'scripts/setup.ps1') @parameters
        Assert-Sequence $global:CloudStartupTestState.Native.ToArray() @('python-version','pip-chroma','npm-ci','npm-ci') 'setup ran an unexpected installer'
        Assert-CloudTest ($global:CloudStartupTestState.Processes.Count -eq 0) 'setup started a database for existing data'
        Assert-CloudTest ($global:CloudStartupTestState.Waits.Count -eq 0) 'setup waited for a model or service'
        Assert-CloudTest ([IO.File]::ReadAllText($envPath) -ceq $fixture) 'setup changed existing app.env'
        Assert-CloudTest ([IO.File]::ReadAllText((Join-Path $script:LocalRoot 'mysql/my.ini')) -ceq 'fixture-sentinel') 'setup rewrote existing MySQL config'
    }
    foreach ($mode in @('default','legacy','external-infrastructure')) {
        Reset-TestState
        foreach ($name in $legacyNames) { [Environment]::SetEnvironmentVariable($name, 'inherited-legacy-fixture', 'Process') }
        $parameters = @{ MySqlBin = $mysqlBin }
        if ($mode -eq 'legacy') { $parameters.ExternalModels = $true; $parameters.SkipBuild = $true }
        if ($mode -eq 'external-infrastructure') { $parameters.ExternalInfrastructure = $true; $parameters.SkipBuild = $true }
        & (Join-Path $testRoot 'scripts/start.ps1') @parameters
        $expectedProcesses = if ($mode -eq 'external-infrastructure') { @('backend','chat-web','docs-web') } else { @('mysql','chroma','backend','chat-web','docs-web') }
        Assert-Sequence $global:CloudStartupTestState.Processes.ToArray() $expectedProcesses 'start launched incorrect processes'
        $expectedNative = if ($mode -eq 'default') { @('mysql-ping','mvn-package','npm-build','npm-build') } elseif ($mode -eq 'legacy') { @('mysql-ping') } else { @() }
        Assert-Sequence $global:CloudStartupTestState.Native.ToArray() $expectedNative 'start ran incorrect native commands'
        Assert-Sequence $global:CloudStartupTestState.Waits.ToArray() @('http://127.0.0.1:18000/api/v2/heartbeat','http://127.0.0.1:8088/api/health','http://127.0.0.1:5173','http://127.0.0.1:5174') 'start waited on incorrect endpoints'
        Assert-CloudTest $global:CloudStartupTestState.BackendEnvironmentClear 'Backend inherited stale model environment'
        Assert-CloudTest ([IO.File]::ReadAllText($envPath) -ceq $fixture) 'start changed existing app.env'
    }
    Write-Host "PASS: $checks cloud startup assertions; parsing, allowlist, inherited-env isolation, setup/start entrypoints and compatibility switches. No real services or APIs used."
} finally {
    foreach ($name in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
        if (-not $originalEnvironment.Contains($name)) { Remove-Item -LiteralPath ("Env:$name") -ErrorAction SilentlyContinue }
    }
    foreach ($name in $originalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
    Remove-Variable -Name CloudStartupTestState -Scope Global -ErrorAction SilentlyContinue
    # Check the absolute deletion target; use one shell end-to-end.
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ((Split-Path $resolvedTestRoot -Parent).TrimEnd('\','/') -ne $tempParent.TrimEnd('\','/') -or
        (Split-Path $resolvedTestRoot -Leaf) -notlike 'rag-cloud-startup-*') {
        throw 'Refusing to clean a fixture directory outside the intended temporary parent.'
    }
    if (Test-Path -LiteralPath $resolvedTestRoot) { Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force }
}
