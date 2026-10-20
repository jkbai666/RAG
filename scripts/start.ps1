param([switch]$SkipBuild, [switch]$ExternalModels, [switch]$ExternalInfrastructure, [string]$MySqlBin)
. (Join-Path $PSScriptRoot 'Common.ps1')
Import-AppEnvironment
$runningRecord = Join-Path $script:LocalRoot 'backend.process.json'
if (-not $SkipBuild -and (Test-Path -LiteralPath $runningRecord)) {
    $running = Get-Content -LiteralPath $runningRecord -Raw | ConvertFrom-Json
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($running.pid)" -ErrorAction SilentlyContinue
    if ($current -and $current.CommandLine -and $current.CommandLine.Contains($running.marker)) {
        Write-Host 'Backend is already running; reusing builds. Stop the project before rebuilding changed sources.'
        $SkipBuild = $true
    }
}
if (-not $ExternalInfrastructure) {
    if (-not $MySqlBin) { $MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin' }
    $config = Join-Path $script:LocalRoot 'mysql\my.ini'
    if (-not (Test-Path -LiteralPath $config)) { throw 'Run ./scripts/setup.ps1 first.' }
    Start-ManagedProcess -Name mysql -Executable (Join-Path $MySqlBin 'mysqld.exe') -Arguments @(('--defaults-file="' + $config + '"')) -Marker $config -Port 13306
    $admin = Join-Path $MySqlBin 'mysqladmin.exe'
    $adminConfig = Join-Path $script:LocalRoot 'mysql\admin.cnf'
    $mysqlDeadline = (Get-Date).AddSeconds(90)
    do {
        & $admin "--defaults-extra-file=$adminConfig" ping 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -gt $mysqlDeadline) { throw 'Project MySQL did not become ready. See .local/logs/mysql.log.' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $chroma = Join-Path $script:LocalRoot 'chroma-venv\Scripts\chroma.exe'
    $chromaPath = Join-Path $script:LocalRoot 'chroma'
    Start-ManagedProcess -Name chroma -Executable $chroma -Arguments @('run','--path',('"'+$chromaPath+'"'),'--host','127.0.0.1','--port','8000') -Marker $chromaPath -Port 8000
}
Wait-Http ($env:CHROMA_BASE_URL.TrimEnd('/') + '/api/v2/heartbeat')
# -ExternalModels remains a compatibility no-op. Models and keys are configured in application.yml.
if (-not $SkipBuild) {
    & mvn.cmd -f (Join-Path $script:ProjectRoot 'backend\pom.xml') package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    foreach ($frontend in @('frontend-chat','frontend-docs')) {
        Push-Location (Join-Path $script:ProjectRoot $frontend)
        try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw "$frontend build failed." } } finally { Pop-Location }
    }
}
$jar = Join-Path $script:ProjectRoot 'backend\target\rag-backend-0.0.1-SNAPSHOT.jar'
$java = (Get-Command java).Source
Start-ManagedProcess -Name backend -Executable $java -Arguments @('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"')) -Marker $jar -Port 8088
Wait-Http 'http://127.0.0.1:8088/api/health' 120
$node = (Get-Command node).Source
foreach ($web in @(@('chat-web','frontend-chat',5173),@('docs-web','frontend-docs',5174))) {
    $appRoot = Join-Path $script:ProjectRoot $web[1]
    $vite = Join-Path $appRoot 'node_modules\vite\bin\vite.js'
    $viteConfig = Join-Path $appRoot 'vite.config.ts'
    Start-ManagedProcess -Name $web[0] -Executable $node -Arguments @(('"'+$vite+'"'),'preview','--config',('"'+$viteConfig+'"'),'--host','127.0.0.1','--port',$web[2],'--strictPort',('"'+$appRoot+'"')) -Marker $viteConfig -Port $web[2]
    Wait-Http ("http://127.0.0.1:"+$web[2])
}
Write-Host ''
Write-Host 'Chat:      http://127.0.0.1:5173'
Write-Host 'Documents: http://127.0.0.1:5174'
Write-Host 'Stop only this project: ./scripts/stop.ps1'
