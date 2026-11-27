$ErrorActionPreference = 'Stop'
$frontend = Join-Path $PSScriptRoot '../frontend'
$env:VITE_API_BASE_URL = 'http://localhost:18085'
$env:VITE_WS_BASE_URL = 'ws://localhost:18085'
Push-Location $frontend
try {
    & npm.cmd run dev -- --host localhost --port 5173 --strictPort
    if ($LASTEXITCODE) { throw 'Frontend exited with an error.' }
} finally { Pop-Location }
