#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build dist
# Compile into a fresh directory, then publish by rename. Never truncate a JAR
# that a running JVM may still use for lazy class loading.
staging=$(mktemp -d dist/.rabe-build.XXXXXX)
trap 'rm -rf "$staging"' EXIT
mkdir "$staging/classes"
find src/main/java -name '*.java' > "$staging/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -cp 'lib/*' -d "$staging/classes" @"$staging/sources.txt"
printf 'Main-Class: city.Main\nClass-Path: ../lib/jpbc-api-2.0.0.jar ../lib/jpbc-plaf-2.0.0.jar\n\n' > "$staging/MANIFEST.MF"
jar cfm "$staging/rabe-city.jar" "$staging/MANIFEST.MF" -C "$staging/classes" .
mv -f "$staging/rabe-city.jar" dist/rabe-city.jar
echo 'Built dist/rabe-city.jar (restart nodes to load the new version)'
