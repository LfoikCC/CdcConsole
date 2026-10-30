@echo off
chcp 65001 >nul
setlocal EnableExtensions
title FLUXA CDC Console

rem ---------------------------------------------------------------------------
rem CDC Console launcher.
rem The JVM runs windowless, so this script waits for the port and reports the
rem result instead of closing the window instantly.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
set "JAVA_BIN=C:\Users\lhy\Documents\CodeX\tools\jdk-21.0.12.1+1\bin\javaw.exe"
if not exist "%JAVA_BIN%" set "JAVA_BIN=javaw.exe"
set "JAR=%ROOT%target\cdc-platform-0.1.0-SNAPSHOT.jar"
set "LOGDIR=%ROOT%logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%" >nul 2>&1

netstat -ano | findstr /r /c:":8080 .*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [FLUXA] CDC Console is already running: http://localhost:8080
    echo         Run stop.cmd first if you want a restart.
    ping -n 7 127.0.0.1 >nul
    exit /b 0
)

if not exist "%JAR%" (
    echo [FLUXA] Build artifact not found:
    echo         %JAR%
    echo         Run: mvn -DskipTests -Dskip.frontend=true package
    pause
    exit /b 1
)

echo [FLUXA] Starting CDC Console ...
set "SERVER_PORT=8080"
start "" "%JAVA_BIN%" -jar "%JAR%" --server.port=8080 --logging.file.name=%LOGDIR%\app.log

set /a WAIT=0
:waitloop
ping -n 2 127.0.0.1 >nul
netstat -ano | findstr /r /c:":8080 .*LISTENING" >nul 2>&1
if not errorlevel 1 goto ready
set /a WAIT+=1
if %WAIT% lss 40 goto waitloop

echo.
echo [FLUXA] Startup timed out, nothing is listening on 8080.
echo         Log: %LOGDIR%\app.log
if exist "%LOGDIR%\app.log" powershell -NoProfile -Command "Get-Content -LiteralPath '%LOGDIR%\app.log' -Tail 15"
echo.
pause
exit /b 1

:ready
ping -n 3 127.0.0.1 >nul
echo.
echo [FLUXA] Started: http://localhost:8080
echo         Close this window when ready; the service keeps running.
ping -n 7 127.0.0.1 >nul
exit /b 0
