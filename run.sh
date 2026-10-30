#!/usr/bin/env bash
# Compiles and launches Flappy Bird Redux.
#   ./run.sh         play the game
#   ./run.sh test    run the headless smoke test instead

set -euo pipefail
cd "$(dirname "$0")"

mkdir -p out

javac -d out src/*.java

cp -R resources/. out/

if [[ "${1:-}" == "test" ]]; then
    exec java -Djava.awt.headless=true -cp out src.SmokeTest
fi

exec java -cp out src.TopClass