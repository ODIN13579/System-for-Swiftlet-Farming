param([string]$MavenPath, [switch]$UseBuiltJar)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root 'backend'
if (Get-NetTCPConnection -LocalPort 18085 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 18085 is occupied. Use the existing demo service or close its terminal first.'
}
foreach ($name in 'JWT_SECRET','AI_SERVICE_KEY') {
    if ([Environment]::GetEnvironmentVariable($name)) { continue }
    $settings = Join-Path $root '.env'
    $entry = if (Test-Path -LiteralPath $settings) {
        Get-Content -LiteralPath $settings | Where-Object { $_ -match "^\s*$name\s*=" } | Select-Object -Last 1
    }
    if (!$entry) { throw "Set $name in the environment or in the ignored .env." }
    [Environment]::SetEnvironmentVariable($name, ($entry -split '=', 2)[1].Trim().Trim('"').Trim("'"))
}
$published = @(docker port swiftletcare-demo-db 5432/tcp)
if ($LASTEXITCODE -or $published -notcontains '127.0.0.1:55437') { throw 'Cannot verify demo PostgreSQL mapping.' }
$published = @(docker port swiftletcare-demo-mqtt 1883/tcp)
if ($LASTEXITCODE -or $published -notcontains '127.0.0.1:51887') { throw 'Cannot verify demo broker mapping.' }
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55437/swiftletcare_demo'
$env:DB_USERNAME = docker exec swiftletcare-demo-db printenv POSTGRES_USER
if ($LASTEXITCODE -or !$env:DB_USERNAME) { throw 'Cannot read demo DB username.' }
$env:DB_PASSWORD = docker exec swiftletcare-demo-db printenv POSTGRES_PASSWORD
if ($LASTEXITCODE -or !$env:DB_PASSWORD) { throw 'Cannot read demo DB credential.' }
$existing = docker exec swiftletcare-demo-db sh -c 'psql -AtX -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d swiftletcare_demo -c "select count(*) from swiftletcare.houses"'
if ($LASTEXITCODE -or $existing -notmatch '^\d+$') { throw 'Cannot verify whether the demo database is seeded.' }
if (!$env:LOCAL_ADMIN_PASSWORD) {
    if ([long]$existing -eq 0) { throw 'Set LOCAL_ADMIN_PASSWORD for the initial local database seed.' }
    # Local profile resolves the bootstrap key before checking that a House already exists.
    $env:LOCAL_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
}
$env:LOCAL_RESET_PASSWORD = 'false'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_PORT = '18085'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:FRONTEND_ORIGINS = 'http://localhost:5173'
$env:MQTT_ENABLED = 'true'
$env:MQTT_URL = 'tcp://127.0.0.1:51887'
$env:MQTT_CLIENT_ID = 'swiftletcare-source-demo'
$env:CONTROL_COMMANDS_ENABLED = 'true'
if (!$UseBuiltJar -and !$MavenPath) {
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (!$maven) { throw 'Install Maven or pass -MavenPath with the path to mvn.cmd.' }
    $MavenPath = $maven.Source
}
$output = Join-Path $backend '.build/demo'
$jar = Join-Path $output 'swiftletcare-backend-0.0.1-SNAPSHOT.jar'
Push-Location $backend
try {
    if (!$UseBuiltJar) {
        & $MavenPath "-Dswiftletcare.build.directory=$output" -DskipTests package
        if ($LASTEXITCODE) { throw 'Backend package failed.' }
    }
    if (!(Test-Path -LiteralPath $jar)) { throw 'Built demo jar not found.' }
    & java -jar $jar
    if ($LASTEXITCODE) { throw 'Backend exited with an error.' }
} finally { Pop-Location }
