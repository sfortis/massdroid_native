#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
ADB_BIN="${ADB:-$(command -v adb || true)}"
if [[ -z "$ADB_BIN" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "/mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe"
  do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      ADB_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$ADB_BIN" ]]; then
  echo "adb not found. Set ADB, ANDROID_SDK_ROOT, or ANDROID_HOME."
  exit 1
fi
APK="$HOME/massdroid-native-build/app/outputs/apk/debug/app-debug.apk"

echo "=== Detekt ==="
bash gradlew detekt

echo "=== Build ==="
bash gradlew assembleDebug

echo "=== Install ==="
"$ADB_BIN" install -r "$APK"

echo "=== Done ==="
