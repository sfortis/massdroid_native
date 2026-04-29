#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
BUILD_ROOT="${MASSDROID_BUILD_DIR:-$HOME/massdroid-native-build}"
declare -a TARGET_SERIALS=()
USAGE="Usage: ./build-install.sh [--build-root <path>] [-d|--device <serial>]..."

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
    -d|--device)
      [[ $# -lt 2 ]] && { echo "Missing value for $1"; exit 1; }
      TARGET_SERIALS+=("$2")
      shift 2
      ;;
    --device=*)
      TARGET_SERIALS+=("${1#*=}")
      shift
      ;;
    -h|--help)
      echo "$USAGE"
      echo ""
      echo "  -d, --device <serial>  install on the given device (repeatable)"
      echo "                         if omitted, installs on every connected device"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      echo "$USAGE"
      exit 1
      ;;
  esac
done

VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
APK="$BUILD_ROOT/app/outputs/apk/debug/massdroid-${VERSION}-debug.apk"

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
set -o pipefail
if ! bash gradlew -PmassdroidBuildRoot="$BUILD_ROOT" assembleDebug --no-build-cache 2>&1 | tee /dev/stderr > /dev/null; then
  echo ""
  echo "=== Compilation errors ==="
  DAEMON_LOG=$(ls -t ~/.gradle/daemon/*/daemon-*.out.log 2>/dev/null | head -1)
  if [[ -n "$DAEMON_LOG" ]]; then
    grep "file:///" "$DAEMON_LOG" | tail -20
  fi
  exit 1
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK"
  exit 1
fi

echo "=== Install ==="
echo "Using adb: $ADB_BIN"

mapfile -t CONNECTED < <("$ADB_BIN" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')

if [[ ${#CONNECTED[@]} -eq 0 ]]; then
  echo "No connected devices"
  exit 1
fi

if [[ ${#TARGET_SERIALS[@]} -eq 0 ]]; then
  TARGET_SERIALS=("${CONNECTED[@]}")
else
  for serial in "${TARGET_SERIALS[@]}"; do
    found=0
    for c in "${CONNECTED[@]}"; do
      [[ "$serial" == "$c" ]] && { found=1; break; }
    done
    if [[ $found -eq 0 ]]; then
      echo "Requested device not connected: $serial"
      echo "Connected: ${CONNECTED[*]}"
      exit 1
    fi
  done
fi

PACKAGE_NAME="net.asksakis.massdroidv2.debug"

for serial in "${TARGET_SERIALS[@]}"; do
  echo "-- $serial"
  "$ADB_BIN" -s "$serial" install -r "$APK"
  echo "Relaunching $PACKAGE_NAME"
  "$ADB_BIN" -s "$serial" shell am force-stop "$PACKAGE_NAME"
  "$ADB_BIN" -s "$serial" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || true
done

echo "=== Done ==="
