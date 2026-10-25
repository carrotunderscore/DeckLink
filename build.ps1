# Bygger DeckLink-APK:n med en JDK som Gradle 8.9 klarar (17-21).
$ErrorActionPreference = "Stop"
$jdk = Join-Path $env:USERPROFILE ".jdks\jbr-21.0.11"
if (Test-Path $jdk) { $env:JAVA_HOME = $jdk }
Set-Location (Join-Path $PSScriptRoot "android")
& .\gradlew assembleDebug
Write-Host ""
Write-Host "APK: android\app\build\outputs\apk\debug\app-debug.apk"
