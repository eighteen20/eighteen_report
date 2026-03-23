#!/usr/bin/env sh
set -eu

echo "Starting Eighteen Report with Docker Compose..."
docker compose up -d --build

echo "Done."
echo "App URL: http://127.0.0.1:9876/report"
