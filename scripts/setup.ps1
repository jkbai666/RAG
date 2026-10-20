param([string]$PythonPath, [string]$MySqlBin, [switch]$SkipModels, [string]$DownloadProxy = $env:RAG_DOWNLOAD_PROXY)
. (Join-Path $PSScriptRoot 'Common.ps1')
foreach ($tool in @('java','mvn.cmd','node','npm.cmd')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing prerequisite: $tool. See README.md." }
}
if (-not $MySqlBin) { $MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin' }
$mysqlServer = Join-Path $MySqlBin 'mysqld.exe'
if (-not (Test-Path -LiteralPath $mysqlServer)) { throw 'MySQL 8.4 binaries not found. Pass -MySqlBin.' }
$python = Get-PythonExecutable $PythonPath
$version = & $python -c 'import sys; print(str(sys.version_info.major)+"."+str(sys.version_info.minor))'
if ($version -ne '3.12') { throw "Python 3.12 is required for the pinned Chroma environment; found $version." }
$envFile = Join-Path $script:LocalRoot 'app.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes); $appPassword = ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
    $rng.GetBytes($bytes); $rootPassword = ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
    $rng.Dispose()
    $template = [IO.File]::ReadAllText((Join-Path $script:ProjectRoot '.env.example'))
    $template.Replace('replace-with-a-long-random-password',$appPassword).Replace('replace-with-a-different-long-random-password',$rootPassword) | Set-Content -LiteralPath $envFile -Encoding UTF8
    Write-Host 'Generated private .local/app.env. Keep this file out of version control.'
}
Import-AppEnvironment
$mysqlDir = Join-Path $script:LocalRoot 'mysql'
$dataDir = Join-Path $mysqlDir 'data'
New-Item -ItemType Directory -Force -Path $mysqlDir | Out-Null
$config = Join-Path $mysqlDir 'my.ini'
if (-not (Test-Path -LiteralPath $config)) {
    $base = (Split-Path $MySqlBin).Replace('\','/')
    $data = $dataDir.Replace('\','/')
    $log = (Join-Path $script:LogRoot 'mysql.log').Replace('\','/')
    @"
[mysqld]
basedir=$base
datadir=$data
port=13306
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
max_connections=40
innodb_buffer_pool_size=128M
log-error=$log
"@ | Set-Content -LiteralPath $config -Encoding UTF8
}
if (-not (Test-Path -LiteralPath (Join-Path $dataDir 'mysql'))) {
    if ((Test-Path -LiteralPath $dataDir) -and (Get-ChildItem -LiteralPath $dataDir -Force | Select-Object -First 1)) { throw 'Nonempty MySQL directory is not initialized. Inspect it manually; refusing to reset data.' }
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    & $mysqlServer "--defaults-file=$config" --initialize-insecure
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL initialization failed.' }
    if ($env:DB_USERNAME -ne 'rag' -or $env:DB_PASSWORD -notmatch '^[a-zA-Z0-9_-]+$' -or $env:DB_ROOT_PASSWORD -notmatch '^[a-zA-Z0-9_-]+$') { throw 'New local MySQL setup expects user rag and safe generated passwords.' }
    $bootstrap = Join-Path $mysqlDir 'bootstrap.sql'
    @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($env:DB_ROOT_PASSWORD)';
CREATE DATABASE IF NOT EXISTS rag_mvp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'rag'@'127.0.0.1' IDENTIFIED BY '$($env:DB_PASSWORD)';
GRANT ALL PRIVILEGES ON rag_mvp.* TO 'rag'@'127.0.0.1';
"@ | Set-Content -LiteralPath $bootstrap -Encoding UTF8
    Start-ManagedProcess -Name mysql -Executable $mysqlServer -Arguments @(('--defaults-file="' + $config + '"'),('--init-file="' + $bootstrap + '"')) -Marker $config -Port 13306
}
$adminFile = Join-Path $mysqlDir 'admin.cnf'
if (-not (Test-Path -LiteralPath $adminFile)) {
    @"
[client]
user=root
password=$($env:DB_ROOT_PASSWORD)
host=127.0.0.1
port=13306
protocol=TCP
"@ | Set-Content -LiteralPath $adminFile -Encoding UTF8
}
$venv = Join-Path $script:LocalRoot 'chroma-venv'
$venvPython = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) { & $python -m venv $venv; if ($LASTEXITCODE -ne 0) { throw 'Python venv creation failed.' } }
& $venvPython -m pip install --disable-pip-version-check 'chromadb==1.5.9'
if ($LASTEXITCODE -ne 0) { throw 'Chroma installation failed.' }
# -SkipModels and -DownloadProxy remain accepted for old commands; cloud startup needs neither.
foreach ($frontend in @('frontend-chat','frontend-docs')) {
    Push-Location (Join-Path $script:ProjectRoot $frontend)
    try { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw "$frontend npm ci failed." } } finally { Pop-Location }
}
Write-Host 'Setup complete. Run ./scripts/start.ps1. Existing database and credentials were preserved.'
