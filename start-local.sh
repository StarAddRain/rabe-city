#!/bin/sh
set -eu
cd "$(dirname "$0")"
[ -f config/local/curator.properties ] || sh setup-local.sh
mkdir -p runtime/logs
children=""
cleanup() { for pid in $children; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
trap 'exit 0' INT TERM
for role in curator cloud owner user; do
  java -Xmx2g -jar dist/rabe-city.jar "config/local/$role.properties" > "runtime/logs/$role.log" 2>&1 &
  children="$children $!"
done
printf '%s\n' 'D 注册管理：http://localhost:8083' 'A 拥有者：http://localhost:8081' 'B 云端：http://localhost:8080' 'C 用户：http://localhost:8082' '先在 D 注册车辆；首次公共参数生成需要等待。日志位于 runtime/logs。Ctrl+C 停止本次启动的四个端。'
wait
