#!/usr/bin/env sh
set -eu

echo "WARNING: This will stop containers and REMOVE MySQL data volume."
echo "Type 'yes' to continue:"
read -r confirm

if [ "$confirm" != "yes" ]; then
  echo "Cancelled."
  exit 0
fi

echo "Resetting environment (including database data)..."
docker compose down -v
docker compose up -d --build
echo "Done."
echo "App URL: http://127.0.0.1:9876/report"
