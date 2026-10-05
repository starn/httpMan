#!/usr/bin/env bash
# Builds the standalone jar: target/httpman.jar  (requires JDK 11+).
# Uses Maven when available, otherwise falls back to plain javac + jar.
set -euo pipefail
cd "$(dirname "$0")"

if command -v mvn >/dev/null 2>&1; then
  mvn -q clean package
else
  echo "Maven not found, building with javac/jar"
  rm -rf target
  mkdir -p target/classes
  javac --release 11 -encoding UTF-8 -d target/classes $(find src/main/java -name '*.java')
  printf 'Main-Class: httpman.HttpMan\n' > target/MANIFEST.MF
  jar cfm target/httpman.jar target/MANIFEST.MF -C target/classes .
fi

echo "Built target/httpman.jar  ->  run with: java -jar target/httpman.jar  (or ./run.sh)"
