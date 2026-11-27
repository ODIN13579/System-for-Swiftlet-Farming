$ErrorActionPreference = 'Stop'
$firmware = Join-Path $PSScriptRoot '../firmware'
$sql = "select h.id, z.id, d.device_id from swiftletcare.devices d join swiftletcare.zones z on z.id=d.zone_id join swiftletcare.houses h on h.id=z.house_id where d.device_id='ESP32-LOCAL' and d.type='ESP32' and d.enabled and z.active and h.active"
$identity = @(docker exec -e "SC_DEMO_SQL=$sql" swiftletcare-demo-db sh -c 'psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d swiftletcare_demo -c "$SC_DEMO_SQL"')
if ($LASTEXITCODE -or $identity.Count -ne 1 -or $identity[0] -notmatch '^(\d+)\|(\d+)\|(ESP32-LOCAL)$') {
    throw 'Cannot verify a unique active ESP32-LOCAL in swiftletcare_demo.'
}
$house, $zone, $device = $identity[0].Split('|')
$pio = Get-Command pio -ErrorAction SilentlyContinue
$pioPath = if ($pio) { $pio.Source } else { Join-Path $env:USERPROFILE '.platformio/penv/Scripts/pio.exe' }
if (!(Test-Path -LiteralPath $pioPath)) { throw 'PlatformIO executable not found.' }
$config = Join-Path $firmware '.pio'
New-Item -ItemType Directory -Path $config -Force | Out-Null
@"
#define SC_MQTT_PORT 51887
#define SC_HOUSE_ID "$house"
#define SC_ZONE_ID "$zone"
#define SC_DEVICE_ID "$device"
"@ | Set-Content -LiteralPath (Join-Path $config 'swiftletcare-demo.h') -Encoding ASCII
& $pioPath run -d $firmware -e esp32demo
if ($LASTEXITCODE) { throw 'Demo firmware build failed.' }
Write-Output "MQTT topic: swiftletcare/$house/$zone/$device"
Get-FileHash -LiteralPath (Join-Path $firmware '.pio/build/esp32demo/firmware.bin') -Algorithm SHA256
