#!/usr/bin/env sh
set -eu

echo "Building Spring Boot jar locally..."
./gradlew clean bootJar -x test

echo "Done."
echo "Jar output:"
ls -lh build/libs/*.jar
echo ""
echo "Next:"
echo "1) Upload project (including build/libs/*.jar) to server"
echo "2) Run: docker compose up -d --build"
