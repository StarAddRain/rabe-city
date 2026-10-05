@echo off
cd /d "%~dp0"
java -jar dist\rabe-city.jar prepare config/local http://127.0.0.1:8080 128 50 http://127.0.0.1:8083
if errorlevel 1 (echo Configuration failed. & pause & exit /b 1)
echo Configuration ready. Run start-local.bat.
pause
