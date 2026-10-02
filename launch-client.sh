#!/bin/bash
# Запуск dev-клиента: launch-client.sh <rundir> <demo-mode> [username] [server]
# Java берётся из $JAVA (по умолчанию — Java 17 из Prism Launcher).
cd "$(dirname "$0")"
DIR="$1"; MODE="$2"; USER="${3:-Dev}"; SERVER="$4"
mkdir -p "$DIR"
JAVA="${JAVA:-$HOME/Library/Application Support/PrismLauncher/java/java-runtime-gamma/bin/java}"
ARGS=(--username "$USER")
if [ -n "$SERVER" ]; then ARGS+=(--quickPlayMultiplayer "$SERVER"); else ARGS+=(--quickPlaySingleplayer "New World"); fi
cd "$DIR"
MAC_ARGS=(); [ "$(uname)" = Darwin ] && MAC_ARGS=(-XstartOnFirstThread)
exec "$JAVA" "${MAC_ARGS[@]}" -Xmx3G \
  -Dfabric.dli.config="$OLDPWD/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=client \
  -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient \
  -Dworldmap.demo="$MODE" \
  -cp "$(cat "$OLDPWD/build/client-classpath.txt")" net.fabricmc.devlaunchinjector.Main "${ARGS[@]}"
