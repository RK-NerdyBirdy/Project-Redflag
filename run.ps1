#!/usr/bin/env pwsh
# ==========================================================================
# Project Redflag - PowerShell Launch Script
# Usage: .\run.ps1 [-Port COM8] [-TcpPort 9876] [-WebPort 8080] [-DbUrl "jdbc:sqlite:redflag.db"]
# ==========================================================================

param(
    [string]$Port    = "COM8",
    [string]$TcpPort = "9876",
    [string]$WebPort = "8080",
    [string]$DbUrl   = "jdbc:sqlite:redflag.db",
    [string]$DbUser  = "",
    [string]$DbPass  = ""
)

$ErrorActionPreference = "Stop"

# --------------------------------------------------------------------------
# Locate Java
# --------------------------------------------------------------------------
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $candidates = @(
        "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Microsoft\jdk-21.0.5.11-hotspot"
    )
    foreach ($c in $candidates) {
        if (Test-Path "$c\bin\java.exe") { $javaHome = $c; break }
    }
}
if (-not $javaHome -or -not (Test-Path "$javaHome\bin\java.exe")) {
    Write-Error "[REDFLAG] ERROR: Cannot find JDK 21. Set JAVA_HOME or install Adoptium JDK 21."
    exit 1
}
$java = "$javaHome\bin\java.exe"
Write-Host "[REDFLAG] Using Java: $javaHome" -ForegroundColor Cyan

# --------------------------------------------------------------------------
# Locate JavaFX
# --------------------------------------------------------------------------
$m2 = if ($env:USERPROFILE) { "$env:USERPROFILE\.m2\repository" } else { "$HOME\.m2\repository" }
$jfxBase = "$m2\org\openjfx"
$jfxVer  = "21.0.2"
$osArch  = if ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)) { "win" } else { "linux" }

$jfxModules = @("javafx-controls","javafx-fxml","javafx-graphics","javafx-base","javafx-media","javafx-swing")
$jfxJars = @()
foreach ($mod in $jfxModules) {
    $jar = "$jfxBase\$mod\$jfxVer\${mod}-${jfxVer}-${osArch}.jar"
    if (Test-Path $jar) { $jfxJars += $jar }
}

if ($jfxJars.Count -eq 0) {
    Write-Host "[REDFLAG] JavaFX jars not found in Maven repo. Running mvn compile first..." -ForegroundColor Yellow
    & "$PSScriptRoot\.tools\apache-maven-3.9.9\bin\mvn.cmd" -f "gateway/pom.xml" compile -q
    foreach ($mod in $jfxModules) {
        $jar = "$jfxBase\$mod\$jfxVer\${mod}-${jfxVer}-${osArch}.jar"
        if (Test-Path $jar) { $jfxJars += $jar }
    }
}

$jfxPath = $jfxJars -join [System.IO.Path]::PathSeparator
Write-Host "[REDFLAG] JavaFX module path: $($jfxJars.Count) JARs found" -ForegroundColor Cyan

# --------------------------------------------------------------------------
# Locate fat JAR
# --------------------------------------------------------------------------
$fatJar = "gateway\target\redflag-gateway-fat.jar"
if (-not (Test-Path $fatJar)) {
    Write-Host "[REDFLAG] Fat JAR not found. Building with mvn package..." -ForegroundColor Yellow
    & "$PSScriptRoot\.tools\apache-maven-3.9.9\bin\mvn.cmd" -f "gateway/pom.xml" package -DskipTests -q
}

if (-not (Test-Path $fatJar)) {
    Write-Error "[REDFLAG] Build failed. $fatJar not found."
    exit 1
}

Write-Host "[REDFLAG] Fat JAR: $fatJar ($([math]::Round((Get-Item $fatJar).Length / 1MB, 1)) MB)" -ForegroundColor Cyan

# --------------------------------------------------------------------------
# JVM Arguments
# --------------------------------------------------------------------------
$jvmArgs = @(
    "--module-path", $jfxPath,
    "--add-modules", "javafx.controls,javafx.fxml,javafx.graphics,javafx.media",
    "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
    "--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "-Xms128m",
    "-Xmx512m",
    "-Drf.port=$Port",
    "-Drf.tcp.port=$TcpPort",
    "-Drf.web.port=$WebPort",
    "-Drf.db.url=$DbUrl"
)

if ($DbUser) { $jvmArgs += "-Drf.db.user=$DbUser" }
if ($DbPass) { $jvmArgs += "-Drf.db.pass=$DbPass" }

# --------------------------------------------------------------------------
# Launch
# --------------------------------------------------------------------------
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Red
Write-Host "  PROJECT REDFLAG - LAUNCHING EDGE GATEWAY" -ForegroundColor Red
Write-Host "=====================================================" -ForegroundColor Red
Write-Host "  Serial Port : $Port" -ForegroundColor White
Write-Host "  TCP Port    : $TcpPort  (ESP32 WiFi devices)" -ForegroundColor White
Write-Host "  Web Console : http://localhost:$WebPort/" -ForegroundColor White
Write-Host "  Database    : $DbUrl" -ForegroundColor White
Write-Host "  Default Login: admin@redflag.local / Admin@1234" -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Red
Write-Host ""

$allArgs = $jvmArgs + @("-cp", $fatJar, "edu.redflag.fx.Launcher")
& $java @allArgs
