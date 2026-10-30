@echo off
chcp 65001 >nul
setlocal EnableExtensions
title FLUXA CDC Console - stop

for /f "tokens=5" %%P in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do set "PID=%%P"
if not defined PID (
    echo [FLUXA] Nothing is listening on 8080.
    ping -n 5 127.0.0.1 >nul
    exit /b 0
)

echo [FLUXA] Stopping CDC Console ^(PID %PID%^) ...
taskkill /PID %PID% /F >nul 2>&1
ping -n 3 127.0.0.1 >nul
netstat -ano | findstr /r /c:":8080 .*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [FLUXA] Port 8080 is still in use. Check manually with: netstat -ano ^| findstr :8080
    pause
    exit /b 1
)
echo [FLUXA] Stopped.
ping -n 4 127.0.0.1 >nul
exit /b 0
