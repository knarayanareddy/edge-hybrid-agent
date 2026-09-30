#!/usr/bin/env bash
#
# On-device test loop for edge-hybrid-agent.
#
# Brings up a headless API 34 emulator, builds, and runs the full test suite:
#   1. JVM unit tests            (fast, no device)
#   2. instrumentation tests    (real Android framework on device)
#   3. optional: install and smoke-launch the APK
#
# Usage:
#   tools/device_test.sh              # unit + instrumentation
#   tools/device_test.sh --install    # also install + launch the APK
#
# This host's DNS does not work for libc callers, so /tmp/gradle-hosts.txt supplies the
# mappings the JVM needs. Regenerate it if a new host is required.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD_NAME="${EDGE_AVD_NAME:-edge_test}"
HOSTS_FILE="${EDGE_HOSTS_FILE:-/tmp/gradle-hosts.txt}"

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17/Contents/Home}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
GRADLE="${GRADLE:-$HOME/tools/gradle-8.9/bin/gradle}"

if [ -f "$HOSTS_FILE" ]; then
  export JAVA_TOOL_OPTIONS="-Djdk.net.hosts.file=$HOSTS_FILE"
fi

DO_INSTALL=0
[ "${1:-}" = "--install" ] && DO_INSTALL=1

adb="$SDK/platform-tools/adb"
emulator="$SDK/emulator/emulator"

log() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

emulator_up() {
  "$adb" devices 2>/dev/null | grep -q '^emulator-.*device$'
}

start_emulator() {
  if emulator_up; then
    log "Emulator already running"
    return 0
  fi

  if [ ! -d "$HOME/.android/avd/$AVD_NAME.avd" ]; then
    log "AVD '$AVD_NAME' not found"
    echo "Create it with:"
    echo "  sdkmanager --install 'system-images;android-34;google_apis;arm64-v8a'"
    echo "  echo no | avdmanager create avd -n $AVD_NAME \\"
    echo "    -k 'system-images;android-34;google_apis;arm64-v8a' -d pixel_7"
    return 1
  fi

  log "Starting emulator '$AVD_NAME' (headless)"
  # -no-window so this runs unattended; -wipe-data for a clean state per run.
  "$emulator" -avd "$AVD_NAME" \
    -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot -wipe-data \
    >/tmp/emulator-$AVD_NAME.log 2>&1 &

  log "Waiting for boot (this takes 60-90s on first run)"
  for _ in $(seq 1 60); do
    if [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      log "Booted"
      return 0
    fi
    sleep 3
  done

  log "Emulator did not finish booting"
  echo "Check /tmp/emulator-$AVD_NAME.log"
  return 1
}

log "JVM unit tests"
"$GRADLE" -p "$REPO_ROOT" :app:testDebugUnitTest --no-daemon 2>&1 | tail -15

start_emulator || exit 1

export ANDROID_SERIAL="$("$adb" devices | awk '/^emulator-/ {print $1; exit}')"
log "Running instrumentation tests on $ANDROID_SERIAL"
"$GRADLE" -p "$REPO_ROOT" :app:connectedDebugAndroidTest --no-daemon 2>&1 | tail -25

if [ "$DO_INSTALL" = "1" ]; then
  log "Installing and smoke-launching the APK"
  "$GRADLE" -p "$REPO_ROOT" :app:assembleDebug --no-daemon 2>&1 | tail -3
  "$adb" install -r -t "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -2
  "$adb" logcat -c
  "$adb" shell am start -n com.edgehybrid.agent/.MainActivity | tail -2
  sleep 6
  echo
  echo "Resumed activity:"
  "$adb" shell dumpsys activity activities | grep -E 'topResumedActivity' | head -1
  echo
  echo "Crashes:"
  "$adb" logcat -d -b crash | grep -i 'edgehybrid' | head -10 || echo "  none"
  echo
  echo "Screenshot: $REPO_ROOT/app/build/screenshots/device.png"
  mkdir -p "$REPO_ROOT/app/build/screenshots"
  "$adb" exec-out screencap -p > "$REPO_ROOT/app/build/screenshots/device.png"
fi

log "Done"