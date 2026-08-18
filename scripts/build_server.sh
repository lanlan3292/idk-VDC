#!/usr/bin/env bash
# Build vdserver.jar containing classes.dex for app_process
set -e

SDK="${1:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if command -v cygpath >/dev/null 2>&1 && [ -n "$SDK" ]; then
  SDK=$(cygpath -u "$SDK" 2>/dev/null || echo "$SDK")
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "Usage: $0 /path/to/android-sdk  (or set ANDROID_HOME)"
  exit 1
fi

ANDROID_JAR=""
for c in "$SDK/platforms/android-34/android.jar" \
         "$SDK/platforms/android-35/android.jar" \
         "$SDK/platforms/android-33/android.jar"; do
  [ -f "$c" ] && ANDROID_JAR="$c" && break
done
[ -z "$ANDROID_JAR" ] && ANDROID_JAR=$(find "$SDK/platforms" -name android.jar 2>/dev/null | sort -r | head -1)
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found"; exit 1; }

D8=""
for d in "$SDK"/build-tools/*/d8; do
  [ -x "$d" ] && D8="$d" && break
done
[ -n "$D8" ] || { echo "d8 not found, install build-tools"; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/server/src/main/java"
CLASSES="$ROOT/server/build/classes"
DEXDIR="$ROOT/server/build/dex"
JAR_OUT="$ROOT/server/build/libs"

rm -rf "$CLASSES" "$DEXDIR"
mkdir -p "$CLASSES" "$DEXDIR" "$JAR_OUT"

SOURCES_LIST=$(mktemp)
( cd "$SRC" && find . -name "*.java" | sort ) > "$SOURCES_LIST"
echo "Compiling $(wc -l < "$SOURCES_LIST") sources ..."
( cd "$SRC" && javac -encoding UTF-8 -source 11 -target 11 -classpath "$ANDROID_JAR" -d "$CLASSES" @"$SOURCES_LIST" )
rm -f "$SOURCES_LIST"

echo "Running d8 ..."
# shellcheck disable=SC2046
"$D8" --classpath "$ANDROID_JAR" --output "$DEXDIR" $(find "$CLASSES" -name "*.class")

[ -f "$DEXDIR/classes.dex" ] || { echo "classes.dex missing"; exit 1; }

JAR="$JAR_OUT/vdserver.jar"
rm -f "$JAR"
( cd "$DEXDIR" && jar cf "$JAR" classes.dex )
echo "Done: $JAR ($(wc -c < "$JAR") bytes)"
echo "  adb push $JAR /data/local/tmp/vdserver.jar"
echo "  adb shell CLASSPATH=/data/local/tmp/vdserver.jar app_process /system/bin com.vdcontroller.server.Server --name=vdcontroller"
