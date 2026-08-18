#!/system/bin/sh
# Start VD Server as shell user (run via adb shell)
#
# Usage:
#   adb push server/build/libs/vdserver.jar /data/local/tmp/
#   adb push scripts/start_server.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/start_server.sh

JAR=/data/local/tmp/vdserver.jar
SOCKET_NAME=vdcontroller

if [ ! -f "$JAR" ]; then
  echo "ERROR: $JAR not found. Push vdserver.jar first."
  exit 1
fi

# Kill previous instance if any
pkill -f "com.vdcontroller.server.Server" 2>/dev/null

echo "Starting VdServer..."
CLASSPATH="$JAR" app_process /system/bin com.vdcontroller.server.Server --name="$SOCKET_NAME" &
echo "Server started (pid $!). Socket: localabstract:$SOCKET_NAME"
