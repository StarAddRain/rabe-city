#!/bin/sh
set -eu
cd "$(dirname "$0")"
java -jar dist/rabe-city.jar prepare config/local http://127.0.0.1:8080 128 50 http://127.0.0.1:8083
