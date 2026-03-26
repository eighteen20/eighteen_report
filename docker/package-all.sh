#!/usr/bin/env sh
# 本机一键打包（前端 + 后端），并将生成的 jar 放到 docker/ 目录下。
#
# 使用方式（仓库根目录执行）：
#   ./docker/package-all.sh
#
# 产物：
# - docker/app.jar（供 docker build 使用）

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

FRONTEND_DIR="$REPO_ROOT/frontend"
BACKEND_DIR="$REPO_ROOT/eighteen_report"

if [ ! -d "$FRONTEND_DIR" ]; then
  echo "错误：未找到 frontend/：$FRONTEND_DIR"
  exit 1
fi

if [ ! -d "$BACKEND_DIR" ]; then
  echo "错误：未找到 eighteen_report/：$BACKEND_DIR"
  exit 1
fi

echo "== 1/3 打包前端（npm run build）=="
if [ -d "$FRONTEND_DIR/node_modules" ]; then
  (cd "$FRONTEND_DIR" && npm run build)
else
  echo "提示：frontend/node_modules 不存在，将尝试 npm install（可能较慢）"
  (cd "$FRONTEND_DIR" && npm install)
  (cd "$FRONTEND_DIR" && npm run build)
fi

echo "== 2/3 打包后端 jar（./gradlew bootJar -x test）=="
(cd "$BACKEND_DIR" && ./gradlew clean bootJar -x test)

echo "== 3/3 拷贝 jar 到 docker/app.jar =="
JAR_PATH="$(ls -1 "$BACKEND_DIR/build/libs/"*.jar 2>/dev/null | head -n 1 || true)"
if [ -z "$JAR_PATH" ]; then
  echo "错误：未找到 build/libs/*.jar，请先检查后端构建输出。"
  exit 1
fi

cp -f "$JAR_PATH" "$SCRIPT_DIR/app.jar"
echo "完成：$SCRIPT_DIR/app.jar"

echo ""
echo "下一步（服务器只需 build/run 镜像）："
echo "  cd $SCRIPT_DIR"


