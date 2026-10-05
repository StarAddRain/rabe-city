@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "staging=build\rabe-build-%RANDOM%-%RANDOM%"
mkdir "%staging%\classes"
if not exist dist mkdir dist
(for /r src\main\java %%F in (*.java) do (
 set "sourcePath=%%F"
 set "sourcePath=!sourcePath:%cd%\=!"
 echo "!sourcePath:\=/!"
)) > build\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "lib/*" -d %staging%\classes @build\sources.txt
if errorlevel 1 exit /b 1
(echo Main-Class: city.Main& echo Class-Path: ../lib/jpbc-api-2.0.0.jar ../lib/jpbc-plaf-2.0.0.jar& echo.) > build\MANIFEST.MF
jar cfm "%staging%\rabe-city.jar" build\MANIFEST.MF -C "%staging%\classes" .
if errorlevel 1 exit /b 1
move /y "%staging%\rabe-city.jar" dist\rabe-city.jar >nul
if errorlevel 1 (
 echo Could not replace JAR. Stop the running nodes and build again.
 exit /b 1
)
rmdir /s /q "%staging%"
echo Built dist\rabe-city.jar. Restart nodes to load the new version.
