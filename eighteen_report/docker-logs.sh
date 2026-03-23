#!/usr/bin/env sh
set -eu

# 用法：
#   ./docker-logs.sh        -> 跟随全部服务日志
#   ./docker-logs.sh app    -> 跟随 app 服务日志
#   ./docker-logs.sh mysql  -> 跟随 mysql 服务日志

service="${1:-}"

if [ -n "$service" ]; then
  docker compose logs -f "$service"
else
  docker compose logs -f
fi
