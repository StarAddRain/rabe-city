@echo off
cd /d "%~dp0"
if not exist config\local\curator.properties (
 java -jar dist\rabe-city.jar prepare config/local http://127.0.0.1:8080 32 50 http://127.0.0.1:8083
 if errorlevel 1 (pause & exit /b 1)
)
for %%R in (curator cloud owner user) do start "RABE %%R" cmd /k "java -Xmx2g -jar dist\rabe-city.jar config\local\%%R.properties"
echo D: http://localhost:8083
echo A: http://localhost:8081
echo B: http://localhost:8080
echo C: http://localhost:8082
echo Wait for D setup, then register vehicles in D.
pause
