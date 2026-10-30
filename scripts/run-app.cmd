@echo off
cd /d "%~dp0\.."
mvn spring-boot:run -Dskip.frontend=true
