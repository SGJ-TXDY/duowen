# Builds and tests the app with the local offline toolchain.
# Usage:  powershell -ExecutionPolicy Bypass -File tools\build.ps1 [tasks...]
# Default tasks: assembleDebug testDebugUnitTest
#
# Override any location with environment variables before running:
#   EQ_TOOLCHAIN      folder holding jdk-17*, sdk, gradle-8.9
#   EQ_ANDROID_HOME   folder holding debug.keystore (the signing identity of every released APK)
#   EQ_GRADLE_HOME    Gradle cache (already contains every dependency, so builds work offline)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)
$ErrorActionPreference = 'Stop'
if (-not $Tasks) { $Tasks = @('assembleDebug', 'testDebugUnitTest') }

$work = 'C:\Users\woziji\Documents\Codex\2026-10-21\embeddedquiz-app-embeddedquiz-android-app-linux-5\work'
$toolchain = if ($env:EQ_TOOLCHAIN) { $env:EQ_TOOLCHAIN } else { Join-Path $work 'toolchain' }
$androidHome = if ($env:EQ_ANDROID_HOME) { $env:EQ_ANDROID_HOME } else { Join-Path $work 'android-home' }
$gradleHome = if ($env:EQ_GRADLE_HOME) { $env:EQ_GRADLE_HOME } else { Join-Path $work 'gradle-home' }

$jdk = Get-ChildItem $toolchain -Directory -Filter 'jdk-17*' | Select-Object -First 1
if (-not $jdk) { throw "JDK 17 not found under $toolchain" }
if (-not (Test-Path (Join-Path $androidHome 'debug.keystore'))) {
    throw "debug.keystore not found under $androidHome; a different key would block upgrade installs"
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $toolchain 'sdk'
# Gradle signs debug builds with ANDROID_USER_HOME\debug.keystore.
$env:ANDROID_USER_HOME = $androidHome
$env:GRADLE_USER_HOME = $gradleHome
# Robolectric's temp databases exceed Windows path limits under deep or non-ASCII folders.
$temp = 'C:\Users\woziji\Documents\Codex\eqtmp'
New-Item -ItemType Directory -Force $temp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$temp"

$project = Split-Path $PSScriptRoot -Parent
# Test workers cannot load classes from a non-ASCII path, so build through an ASCII junction.
if ($project -match '[^\x00-\x7F]') {
    $link = if ($env:EQ_ASCII_LINK) { $env:EQ_ASCII_LINK } else { 'C:\AndroidProjects\EmbeddedQuiz' }
    if (-not (Test-Path $link)) {
        New-Item -ItemType Directory -Force (Split-Path $link -Parent) | Out-Null
        New-Item -ItemType Junction -Path $link -Target $project | Out-Null
    }
    $project = $link
}
Set-Location $project
& (Join-Path $toolchain 'gradle-8.9\bin\gradle.bat') @Tasks --offline --console=plain
exit $LASTEXITCODE
