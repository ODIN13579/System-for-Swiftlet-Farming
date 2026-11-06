[CmdletBinding()]
param([switch]$Java21Compatibility, [switch]$Offline, [switch]$IsolatedBuild,
    [ValidatePattern('^build2_[a-z0-9_]+$')][string]$TestDatabase = 'build2_test')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$names = @('DB_URL','DB_USERNAME','DB_PASSWORD','DB_TEST_ENABLED','TEST_MQTT_URL',
    'SPRING_PROFILES_ACTIVE','SPRING_FLYWAY_TARGET','DEBUG','LOGGING_LEVEL_ROOT','LOGGING_LEVEL_ORG_SPRINGFRAMEWORK')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repoRoot
try {
    # Windows PowerShell can turn native stderr progress into a terminating error
    # when the caller redirects output. Check the native exit code explicitly.
    $ErrorActionPreference = 'Continue'
    docker compose -p swiftletcare-build2-test -f compose.build2-test.yml up -d --wait
    $dockerExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($dockerExit -ne 0) { throw 'Cannot start isolated BUILD 2 test services' }
    $env:DB_URL = "jdbc:postgresql://localhost:55433/$TestDatabase"
    $env:DB_USERNAME = 'build2'
    $env:DB_PASSWORD = 'build2-test-only'
    $env:DB_TEST_ENABLED = 'true'
    $env:TEST_MQTT_URL = 'tcp://localhost:51884'
    $env:DEBUG = 'false'
    $env:LOGGING_LEVEL_ROOT = 'INFO'
    $env:LOGGING_LEVEL_ORG_SPRINGFRAMEWORK = 'INFO'
    Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
    Remove-Item Env:SPRING_FLYWAY_TARGET -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path backend/logs -Force | Out-Null
    $buildRoot = Join-Path $repoRoot 'backend'
    if ($IsolatedBuild) {
        $buildRoot = Join-Path $repoRoot ("backend/logs/isolated-build/" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
        Copy-Item backend/pom.xml -Destination $buildRoot
        Copy-Item backend/src -Destination $buildRoot -Recurse
        Write-Host "Isolated Maven source snapshot: $buildRoot"
    }
    $mavenArgs = @('-f',(Join-Path $buildRoot 'pom.xml'),"-Dmaven.repo.local=$env:USERPROFILE\.m2\repository")
    if ($Offline) { $mavenArgs += '-o' }
    if ($Java21Compatibility) {
        Write-Warning 'Compatibility run on Java 21; this does not verify the Java 25 baseline in pom.xml.'
        $mavenArgs += '-Djava.version=21'
    }
    $mavenArgs += @('clean','verify')
    $ErrorActionPreference = 'Continue'
    & mvn @mavenArgs 2>&1 | Tee-Object -FilePath backend/logs/build2-verify.log
    $mavenExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($mavenExit -ne 0) { throw 'BUILD 2 verification failed; see backend/logs/build2-verify.log' }
    # A successful Maven command with skipped tests is not acceptance evidence.
    $reports = @(Get-ChildItem (Join-Path $buildRoot 'target/surefire-reports') -Filter 'TEST-*.xml')
    $requiredSuites = @('com.swiftletcare.BackendIntegrationTest',
        'com.swiftletcare.IngestionIntegrationTest', 'com.swiftletcare.Build2ManagementIntegrationTest',
        'com.swiftletcare.management.ZoneConfigValidationTest')
    $suiteResults = @()
    foreach ($report in $reports) {
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        if ([int]$suite.testsuite.tests -lt 1) { throw "Empty test suite in $($report.Name)" }
        if ([int]$suite.testsuite.skipped -gt 0) { throw "Skipped tests in $($report.Name)" }
        if ([int]$suite.testsuite.failures -gt 0 -or [int]$suite.testsuite.errors -gt 0) {
            throw "Failing tests in $($report.Name)"
        }
        $suiteResults += [pscustomobject]@{ name = [string]$suite.testsuite.name
            tests = [int]$suite.testsuite.tests; failures = [int]$suite.testsuite.failures
            errors = [int]$suite.testsuite.errors; skipped = [int]$suite.testsuite.skipped }
    }
    foreach ($required in $requiredSuites) {
        if ($suiteResults.name -notcontains $required) { throw "Required regression suite did not run: $required" }
    }
    $summary = [pscustomobject]@{ completedAt = [DateTimeOffset]::Now.ToString('o')
        database = $TestDatabase; buildRoot = $buildRoot; java21Compatibility = [bool]$Java21Compatibility
        tests = ($suiteResults | Measure-Object -Property tests -Sum).Sum
        failures = 0; errors = 0; skipped = 0; suites = $suiteResults }
    $summaryPath = Join-Path $buildRoot 'verification-summary.json'
    [IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    Write-Host "Verified $($summary.tests) tests without failures/errors/skips. Evidence: $summaryPath"
    Write-Host 'Verification completed. Test containers and volumes are preserved.'
}
finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
