#!/usr/bin/env bash
#
# Live monitor for Sendspin seek round-trips.
# Tails logcat from the test phone and prints, for each seek:
#   - the WS cmd/seek the user triggered
#   - the client/state we ship to the Sendspin server
#   - the server's reply (stream/clear vs stream/end + new stream)
#   - time to reach SYNCHRONIZED again
#
# Usage:
#   bash scripts/seek-monitor.sh [serial]
# Defaults to the WiFi test phone at 10.200.200.3:5555.

set -euo pipefail

SERIAL="${1:-10.200.200.3:5555}"
ADB="/mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PID="$($ADB -s "$SERIAL" shell pidof net.asksakis.massdroidv2.debug | tr -d '\r')"

if [[ -z "$PID" ]]; then
  echo "App not running on $SERIAL" >&2
  exit 1
fi

echo "Monitoring seek round-trips on $SERIAL (pid=$PID). Ctrl+C to stop."
echo "─── time ───────  ── event ──"

# Keep only the lines that matter for a seek trace. Strip the noisy
# logcat prefix down to HH:MM:SS.fff for at-a-glance scanning.
"$ADB" -s "$SERIAL" logcat -T 1 2>/dev/null \
  | grep -E " $PID " \
  | grep --line-buffered -E \
      'cmd/seek|expectDiscontinuity|>>> client/state|stream/(end|start|clear)|playback=SYNCHRONIZED|playbackThread track\.play|SYNC_ERROR_REBUFFERING' \
  | awk '{
      ts = $2
      $1=""; $2=""; $3=""; $4=""; $5=""
      sub(/^ +/, "")
      printf "%s  %s\n", ts, $0
    }'
