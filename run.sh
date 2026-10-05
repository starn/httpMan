#!/usr/bin/env bash
# Builds the jar if needed, then starts HttpMan.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f target/httpman.jar ] || [ -n "$(find src pom.xml -newer target/httpman.jar -type f | head -1)" ]; then
  ./buildJar.sh
fi
if [ "$(uname)" = "Darwin" ]; then
  exec java -Xdock:name=HttpMan -jar target/httpman.jar "$@"
fi
exec java -jar target/httpman.jar "$@"
