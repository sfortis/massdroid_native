#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
APK="$HOME/massdroid-native-build/app/outputs/apk/debug/app-debug.apk"

declare -a ADB_CANDIDATES=()

add_adb_candidate() {
  local candidate="$1"
  [[ -z "$candidate" ]] && return
  [[ ! -f "$candidate" ]] && return

  local existing
  for existing in "${ADB_CANDIDATES[@]}"; do
    [[ "$existing" == "$candidate" ]] && return
  done
  ADB_CANDIDATES+=("$candidate")
}

adb_has_device() {
  local adb_bin="$1"
  "$adb_bin" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
}

if [[ -n "${ADB:-}" ]]; then
  add_adb_candidate "$ADB"
fi

shopt -s nullglob
for candidate in /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe; do
  add_adb_candidate "$candidate"
done
shopt -u nullglob

add_adb_candidate "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
add_adb_candidate "${ANDROID_HOME:-}/platform-tools/adb"

if command -v adb >/dev/null 2>&1; then
  add_adb_candidate "$(command -v adb)"
fi

if [[ ${#ADB_CANDIDATES[@]} -eq 0 ]]; then
  echo "adb not found. Set ADB, ANDROID_SDK_ROOT, or ANDROID_HOME."
  exit 1
fi

ADB_BIN=""
for candidate in "${ADB_CANDIDATES[@]}"; do
  if adb_has_device "$candidate"; then
    ADB_BIN="$candidate"
    break
  fi
done

if [[ -z "$ADB_BIN" ]]; then
  ADB_BIN="${ADB_CANDIDATES[0]}"
fi

echo "=== Detekt ==="
bash gradlew detekt

echo "=== Build ==="
bash gradlew assembleDebug

echo "=== Install ==="
echo "Using adb: $ADB_BIN"
"$ADB_BIN" install -r "$APK"

echo "=== Done ==="
