@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: Project Redflag — Windows Batch Launch Script
:: Double-click this file to start the application
:: ============================================================

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
set JAVA=%JAVA_HOME%\bin\java.exe
set M2=%USERPROFILE%\.m2\repository\org\openjfx
set JFX_VER=21.0.2
set FAT_JAR=gateway\target\redflag-gateway-fat.jar

:: Serial port (change to match your Arduino)
set RF_PORT=COM8
:: TCP port for ESP32 WiFi devices
set RF_TCP_PORT=9876
:: Web console port
set RF_WEB_PORT=8080
:: Database (SQLite by default)
set RF_DB_URL=jdbc:sqlite:redflag.db

:: ── Build fat JAR if missing ───────────────────────────────
if not exist "%FAT_JAR%" (
    echo [REDFLAG] Building fat JAR...
    .tools\apache-maven-3.9.9\bin\mvn.cmd -f gateway/pom.xml package -DskipTests -q
    if errorlevel 1 (
        echo [REDFLAG] ERROR: Build failed. Check Maven output.
        pause
        exit /b 1
    )
)

:: ── Build JavaFX module path ───────────────────────────────
set JFX_PATH=
for %%M in (javafx-controls javafx-fxml javafx-graphics javafx-base javafx-media) do (
    set JAR=%M2%\%%M\%JFX_VER%\%%M-%JFX_VER%-win.jar
    if exist "!JAR!" (
        if "!JFX_PATH!"=="" (
            set JFX_PATH=!JAR!
        ) else (
            set JFX_PATH=!JFX_PATH!;!JAR!
        )
    )
)

if "!JFX_PATH!"=="" (
    echo [REDFLAG] ERROR: JavaFX JARs not found in Maven repo.
    echo Run: .tools\apache-maven-3.9.9\bin\mvn.cmd -f gateway/pom.xml compile
    pause
    exit /b 1
)

echo.
echo =====================================================
echo   PROJECT REDFLAG - EDGE GATEWAY
echo =====================================================
echo   Serial Port  : %RF_PORT%
echo   TCP Port     : %RF_TCP_PORT%   (ESP32 WiFi)
echo   Web Console  : http://localhost:%RF_WEB_PORT%/
echo   Database     : %RF_DB_URL%
echo   Login        : admin@redflag.local / Admin@1234
echo =====================================================
echo.

"%JAVA%" ^
  --module-path "%JFX_PATH%" ^
  --add-modules "javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.media" ^
  "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED" ^
  "--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED" ^
  "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  "--add-opens=java.base/java.nio=ALL-UNNAMED" ^
  -Xms128m -Xmx512m ^
  -Drf.port=%RF_PORT% ^
  -Drf.tcp.port=%RF_TCP_PORT% ^
  -Drf.web.port=%RF_WEB_PORT% ^
  -Drf.db.url=%RF_DB_URL% ^
  -cp "%FAT_JAR%" ^
  edu.redflag.fx.Launcher

if errorlevel 1 (
    echo.
    echo [REDFLAG] Application exited with error. Check the console output above.
    pause
)
endlocal
