$ErrorActionPreference = 'Stop'
$db = 'swiftletcare-demo-db'
$published = @(docker port $db 5432/tcp)
if ($LASTEXITCODE -or $published -notcontains '127.0.0.1:55437') { throw 'Cannot verify demo database mapping.' }
$path = '/tmp/swiftletcare-demo-actuators-' + [guid]::NewGuid().ToString('N') + '.sql'
$source = Join-Path $PSScriptRoot '../backend/scripts/bootstrap-demo-actuators.sql'
docker cp $source "${db}:$path"
if ($LASTEXITCODE) { throw 'Cannot transfer bootstrap to the demo container.' }
docker exec -e "SC_BOOTSTRAP_FILE=$path" $db sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d swiftletcare_demo -f "$SC_BOOTSTRAP_FILE"'
if ($LASTEXITCODE) { throw 'Demo bootstrap failed.' }
$sql = "select a.kind,count(*) from swiftletcare.actuators a join swiftletcare.devices d on d.id=a.device_id where d.device_id='ESP32-LOCAL' group by a.kind order by a.kind"
$result = @(docker exec -e "SC_DEMO_SQL=$sql" $db sh -c 'psql -AtX -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d swiftletcare_demo -c "$SC_DEMO_SQL"')
if ($LASTEXITCODE -or ($result -join ',') -ne 'AMPLIFIER|1,FAN|1,HEATER|1,MIST|1') {
    throw 'Expected exactly one actuator of each kind for ESP32-LOCAL.'
}
$result
