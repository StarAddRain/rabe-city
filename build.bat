@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
if not exist dist mkdir dist
(for /r src\main\java %%F in (*.java) do (
 set "sourcePath=%%F"
 set "sourcePath=!sourcePath:%cd%\=!"
 echo "!sourcePath:\=/!"
)) > build\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "lib/*" -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
(echo Main-Class: city.Main& echo Class-Path: ../lib/jpbc-api-2.0.0.jar ../lib/jpbc-plaf-2.0.0.jar& echo.) > build\MANIFEST.MF
jar cfm dist\rabe-city.jar build\MANIFEST.MF -C build\classes .
