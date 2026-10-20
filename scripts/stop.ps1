param([ValidateSet('chat-web','docs-web','backend','chat-model','embedding-model','chroma','mysql')][string[]]$Service = @('chat-web','docs-web','backend','chat-model','embedding-model','chroma','mysql'))
. (Join-Path $PSScriptRoot 'Common.ps1')
foreach ($name in $Service) {
    $recordFile = Join-Path $script:LocalRoot "$name.process.json"
    if (-not (Test-Path -LiteralPath $recordFile)) { continue }
    $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)" -ErrorAction SilentlyContinue
    if (-not $current) { continue }
    if (-not $current.CommandLine -or -not $current.CommandLine.Contains($record.marker)) {
        Write-Warning "Skipping $name because its PID no longer matches the project command line."; continue
    }
    if ($name -eq 'mysql') {
        $admin = Join-Path (Split-Path $record.executable) 'mysqladmin.exe'
        $config = Join-Path $script:LocalRoot 'mysql\admin.cnf'
        & $admin "--defaults-extra-file=$config" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'MySQL graceful shutdown failed; it has not been force-killed.' }
    } else {
        Stop-Process -Id $record.pid -ErrorAction SilentlyContinue
    }
    Write-Host "Stopped $name. Data retained."
}
