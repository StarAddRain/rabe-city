@echo off
cd /d "%~dp0"
if "%~1"=="" (echo Usage: start-node.bat config/local/curator.properties & exit /b 1)
java -Xmx2g -jar dist\rabe-city.jar "%~1"
pause
