param([string]$JavaHome, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 25 JDK, or pass -JavaHome.' }
$root = $PSScriptRoot
Push-Location -LiteralPath $root
try {
    $modVersion = ((Get-Content gradle.properties | Where-Object { $_ -match '^mod_version=' }) -split '=',2)[1].Trim()
    $release = Join-Path $root 'releases'
    New-Item -ItemType Directory -Force -Path $release | Out-Null
    $results = @()
    foreach ($target in (Get-Content versions.json -Raw | ConvertFrom-Json)) {
        $mc = $target.minecraft
        Write-Output "Building JukeBox for Minecraft $mc"
        $task = if ($SkipTests) { 'assemble' } else { 'build' }
        & .\gradlew.bat $task "-Pminecraft_version=$mc" --console plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed for Minecraft $mc" }
        $jarName = "jukebox-$modVersion+$mc.jar"
        Copy-Item -LiteralPath (Join-Path $root "build/libs/$jarName") -Destination (Join-Path $release $jarName)
        $tests = 0
        if (-not $SkipTests) {
            foreach ($testFile in (Get-ChildItem (Join-Path $root 'build/test-results/test') -Filter 'TEST-*.xml')) {
                $xml = [xml](Get-Content -LiteralPath $testFile.FullName -Raw); $tests += [int]$xml.testsuite.tests
            }
        }
        $results += [pscustomobject]@{ minecraft=$mc; fabricApi=$target.fabric; java=[int]$target.java; status='compiled'; tests=$tests; file=$jarName; sha256=(Get-FileHash -LiteralPath (Join-Path $release $jarName) -Algorithm SHA256).Hash.ToLower() }
        $results | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $release 'build-report.json')
    }
    $results | ForEach-Object { "$($_.sha256)  $($_.file)" } | Set-Content -Encoding utf8 (Join-Path $release 'SHA256SUMS.txt')
} finally { Pop-Location }
