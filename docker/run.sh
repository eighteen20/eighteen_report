#!/usr/bin/env sh
# 构建并运行镜像（本地/服务器均可用）。
#
# 说明：
# - 该容器不包含数据库，数据库连接等参数从 application-prod.yaml 读取。
# - 启动时固定选择 prod 配置（SPRING_PROFILES_ACTIVE=prod）。
# - 默认端口映射 9876:9876。

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE_NAME="${IMAGE_NAME:-eighteen-report:local}"
CONTAINER_NAME="${CONTAINER_NAME:-eighteen-report}"
HOST_PORT="${HOST_PORT:-9876}"
SERVER_PORT="${SERVER_PORT:-9876}"
ADD_HOST_GATEWAY="${ADD_HOST_GATEWAY:-1}"
ADD_HOST_ARG=""
if [ "$ADD_HOST_GATEWAY" = "1" ]; then
  ADD_HOST_ARG="--add-host=host.docker.internal:host-gateway"
fi

echo "== build: docker build -t $IMAGE_NAME . =="
cd "$SCRIPT_DIR"
docker build -t "$IMAGE_NAME" .

echo "== run: docker run --rm --name $CONTAINER_NAME ==" 
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
docker run -d --name "$CONTAINER_NAME" \
  $ADD_HOST_ARG \
  -p "${HOST_PORT}:9876" \
  -e "SERVER_PORT=$SERVER_PORT" \
  -e "SPRING_PROFILES_ACTIVE=prod" \
  "$IMAGE_NAME"

echo "访问：http://127.0.0.1:$HOST_PORT/report"

