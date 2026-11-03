[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop')]
    [string]$Action
)

# Disposable database for local tests. It listens on loopback only, and its password is not a secret.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$bin = Join-Path $PSScriptRoot 'postgres\pgsql\bin'
$data = Join-Path $root 'build\test-postgres'
$port = 5433

if (-not (Test-Path -LiteralPath (Join-Path $bin 'pg_ctl.exe'))) {
    throw "Portable Postgres is missing. Extract the Windows binaries zip so that $bin exists."
}

function Test-Running {
    if (-not (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))) { return $false }
    $null = & (Join-Path $bin 'pg_ctl.exe') status -D $data
    return $LASTEXITCODE -eq 0
}

if ($Action -eq 'stop') {
    if (-not (Test-Running)) {
        Write-Output 'Test Postgres is not running.'
        exit 0
    }
    & (Join-Path $bin 'pg_ctl.exe') stop -D $data -m fast
    exit $LASTEXITCODE
}

if (-not (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))) {
    $passwordFile = Join-Path ([System.IO.Path]::GetTempPath()) "neoplayersync-test-pg-$([guid]::NewGuid().ToString('N'))"
    try {
        [System.IO.File]::WriteAllText($passwordFile, 'neoplayersync')
        & (Join-Path $bin 'initdb.exe') -D $data -U neoplayersync --pwfile=$passwordFile --auth=scram-sha-256 -E UTF8 --locale=C
        if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE" }
    } finally {
        Remove-Item -LiteralPath $passwordFile -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Running)) {
    # The server inherits pg_ctl's output handles, so a normal call would wait until the server exits.
    $start = Start-Process -FilePath (Join-Path $bin 'pg_ctl.exe') -WindowStyle Hidden -PassThru -ArgumentList @(
        'start', '-D', "`"$data`"", '-w', '-l', "`"$(Join-Path $data 'server.log')`"", '-o', "`"-p $port -c listen_addresses=127.0.0.1`""
    )
    $null = $start.Handle
    $start.WaitForExit()
    if ($start.ExitCode -ne 0) { throw "Postgres failed to start with exit code $($start.ExitCode)" }
}

$env:PGPASSWORD = 'neoplayersync'
$exists = & (Join-Path $bin 'psql.exe') -h 127.0.0.1 -p $port -U neoplayersync -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'neoplayersync'"
if ($exists -ne '1') {
    & (Join-Path $bin 'createdb.exe') -h 127.0.0.1 -p $port -U neoplayersync neoplayersync
    if ($LASTEXITCODE -ne 0) { throw "createdb failed with exit code $LASTEXITCODE" }
}
Write-Output "Test Postgres is ready: jdbc:postgresql://127.0.0.1:$port/neoplayersync (user and password: neoplayersync)"
