#!/bin/bash
# Запуск dev-клиента: launch-client.sh <rundir> <demo-mode> [username] [server]
cd "$(dirname "$0")"
DIR="$1"; MODE="$2"; USER="${3:-Dev}"; SERVER="$4"
mkdir -p "$DIR"
JAVA="$HOME/Library/Application Support/PrismLauncher/java/java-runtime-gamma/bin/java"
ARGS=(--username "$USER")
if [ -n "$SERVER" ]; then ARGS+=(--quickPlayMultiplayer "$SERVER"); else ARGS+=(--quickPlaySingleplayer "New World"); fi
cd "$DIR"
exec "$JAVA" -XstartOnFirstThread -Xmx3G \
  -Dfabric.dli.config="$OLDPWD/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=client \
  -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient \
  -Dworldmap.demo="$MODE" \
  -cp "$(cat "$OLDPWD/build/client-classpath.txt")" net.fabricmc.devlaunchinjector.Main "${ARGS[@]}"
