#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ "$#" -ne 1 ]; then echo 'Usage: sh start-node.sh config/lan/cloud.properties'; exit 1; fi
exec java -Xmx2g -jar dist/rabe-city.jar "$1"
