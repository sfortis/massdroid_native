#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
BUILD_ROOT="${MASSDROID_BUILD_DIR:-$HOME/massdroid-native-build}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -b|--build-root)
      [[ $# -lt 2 ]] && { echo "Missing value for $1"; exit 1; }
      BUILD_ROOT="$2"
      shift 2
      ;;
    --build-root=*)
      BUILD_ROOT="${1#*=}"
      shift
      ;;
    -h|--help)
      echo "Usage: ./build-install.sh [--build-root <path>]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      echo "Usage: ./build-install.sh [--build-root <path>]"
      exit 1
      ;;
  esac
done

APK="$BUILD_ROOT/app/outputs/apk/debug/app-debug.apk"
LOCAL_APK="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"

declare -a ADB_CANDIDATES=()

is_wsl() {
  [[ -n "${WSL_DISTRO_NAME:-}" ]] && return 0
  grep -qiE "(microsoft|wsl)" /proc/version 2>/dev/null
}

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

if is_wsl; then
  shopt -s nullglob
  for candidate in /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe; do
    add_adb_candidate "$candidate"
  done
  shopt -u nullglob
fi

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
bash gradlew -PmassdroidBuildRoot="$BUILD_ROOT" detekt

echo "=== Build ==="
bash gradlew -PmassdroidBuildRoot="$BUILD_ROOT" assembleDebug

if [[ ! -f "$APK" && -f "$LOCAL_APK" ]]; then
  APK="$LOCAL_APK"
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK"
  exit 1
fi

echo "=== Install ==="
echo "Using adb: $ADB_BIN"
"$ADB_BIN" install -r "$APK"

echo "=== Done ==="
