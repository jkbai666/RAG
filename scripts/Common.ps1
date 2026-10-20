$ErrorActionPreference = 'Stop'
$script:ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:LocalRoot = Join-Path $script:ProjectRoot '.local'
$script:LogRoot = Join-Path $script:LocalRoot 'logs'
New-Item -ItemType Directory -Force -Path $script:LocalRoot,$script:LogRoot | Out-Null

function Import-AppEnvironment {
    $file = Join-Path $script:LocalRoot 'app.env'
    if (-not (Test-Path -LiteralPath $file)) { throw 'Run scripts/setup.ps1 first, or copy .env.example to .local/app.env.' }

    # Spring can bind inherited environment variables even when YAML uses literal values.
    # Clear both the old placeholders and relaxed-binding names before spawning Java.
    $legacyModelNames = @([Environment]::GetEnvironmentVariables('Process').Keys) | Where-Object {
        $_ -match '^(CHAT_|EMBEDDING_|CHUNK_|MODEL_|RETRIEVAL_)' -or
        $_ -eq 'CHROMA_COLLECTION' -or
        $_ -match '^RAG_(CHAT|EMBEDDING|CHUNK|CHROMA_?COLLECTION|TIMEOUT_?SECONDS|MIN_?SCORE)'
    }
    foreach ($name in $legacyModelNames) {
        Remove-Item -LiteralPath ("Env:$name") -ErrorAction SilentlyContinue
    }

    # app.env owns infrastructure only. Never import arbitrary Spring/model overrides.
    $infrastructureNames = @('DB_URL','DB_USERNAME','DB_PASSWORD','DB_ROOT_PASSWORD',
        'CHROMA_BASE_URL','CHROMA_TIMEOUT_SECONDS','PORT')
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        if ($line.Trim().StartsWith('#') -or -not $line.Contains('=')) { continue }
        $pair = $line -split '=', 2
        $name = $pair[0].Trim().ToUpperInvariant()
        if ($infrastructureNames -contains $name) {
            [Environment]::SetEnvironmentVariable($name, $pair[1].Trim(), 'Process')
        }
    }
    $env:ANONYMIZED_TELEMETRY = 'False'
    $env:PYTHONIOENCODING = 'utf-8'
}
function Get-PythonExecutable {
    param([string]$Requested)
    $candidates = @($Requested, (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe'), (Join-Path $HOME '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'))
    $found = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($found) { $candidates += $found.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate) -and $candidate -notlike '*WindowsApps*') { return $candidate }
    }
    throw 'Python 3.12 not found. Install it or pass -PythonPath C:\path\python.exe to setup.ps1.'
}
function Wait-Http {
    param([string]$Url, [int]$Seconds = 90)
    $end = (Get-Date).AddSeconds($Seconds)
    do {
        try { $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3; if ($response.StatusCode -eq 200) { return } } catch {}
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $end)
    throw "Service not ready: $Url. See .local/logs."
}
function Start-ManagedProcess {
    param([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Marker, [int]$Port)
    $recordFile = Join-Path $script:LocalRoot "$Name.process.json"
    if (Test-Path -LiteralPath $recordFile) {
        $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)" -ErrorAction SilentlyContinue
        if ($current -and $current.CommandLine -and $current.CommandLine.Contains($record.marker)) {
            Write-Host "$Name already running (PID $($record.pid))."; return
        }
    }
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is occupied by an unmanaged process. Refusing to reuse or stop it." }
    $stdout = Join-Path $script:LogRoot "$Name.out.log"
    $stderr = Join-Path $script:LogRoot "$Name.err.log"
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $script:ProjectRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    @{pid=$process.Id; marker=$Marker; executable=$Executable; port=$Port; startedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $recordFile -Encoding UTF8
    Write-Host "Started $Name (PID $($process.Id)), listening on 127.0.0.1:$Port."
}
