[CmdletBinding()]
param([string]$Python='python')
$ErrorActionPreference='Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & ./scripts/test-build2.ps1
    $venv=Join-Path ([IO.Path]::GetTempPath()) 'swiftletcare-b250-venv'
    & $Python -m venv $venv
    if ($LASTEXITCODE) {throw 'Cannot create Python venv'}
    $exe=Join-Path $venv 'Scripts/python.exe'
    if (!(Test-Path $exe)) {$exe=Join-Path $venv 'bin/python'}
    & $exe -m pip install -r ./scripts/mqtt-simulator/requirements.txt
    if ($LASTEXITCODE) {throw 'Cannot install MQTT client'}
    & $exe ./scripts/tests/test_b250_wire.py
    if ($LASTEXITCODE) {throw 'MQTT wire test failed'}
    Write-Host 'PASS: Build 2 backend suites + B2-50 MQTT simulator.'
} finally {Pop-Location}
