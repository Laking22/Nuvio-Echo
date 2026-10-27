#!/usr/bin/env bash
# Repeatable performance check on a TV over ADB: cold start time, home-screen frame jank and
# memory. Run it before and after a change to catch slowdowns.
#
# Usage: scripts/perf-check.sh <tv-ip:port> [package]   (package defaults to com.nuviobeta.tv)
#
# Drives only D-pad navigation on the home screen (never OK after the profile pick), so it
# doesn't open or play anything. Picks the first profile on the "Who's watching?" screen.

set -euo pipefail

TARGET="${1:?usage: scripts/perf-check.sh <tv-ip:port> [package]}"
PKG="${2:-com.nuviobeta.tv}"
ACTIVITY="com.nuvio.tv.launcher.AppIconDefault"
a() { adb -s "$TARGET" shell "$@"; }

adb connect "$TARGET" >/dev/null
a am force-stop "$PKG"
sleep 2

echo "== $PKG"
START=$(a am start -W -n "$PKG/$ACTIVITY" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')
echo "cold start (to first frame): ${START} ms"

sleep 6
a input keyevent KEYCODE_DPAD_CENTER   # first profile
sleep 25                               # let catalogs, images and Continue Watching settle

browse() {
  for k in $(printf '22 %.0s' {1..10}) 20 20 20 $(printf '22 %.0s' {1..6}) 19 19 19 $(printf '21 %.0s' {1..10}); do
    a input keyevent "$k"
  done
}

for run in 1 2 3; do
  a dumpsys gfxinfo "$PKG" reset >/dev/null
  browse
  sleep 1
  STATS=$(a dumpsys gfxinfo "$PKG" | tr -d '\r')
  JANK=$(echo "$STATS" | awk -F': ' '/^ *Janky frames:/ {print $2; exit}')
  P50=$(echo "$STATS" | awk -F': ' '/^ *50th percentile:/ {print $2; exit}')
  P90=$(echo "$STATS" | awk -F': ' '/^ *90th percentile:/ {print $2; exit}')
  P99=$(echo "$STATS" | awk -F': ' '/^ *99th percentile:/ {print $2; exit}')
  echo "browse run $run: janky $JANK, p50 $P50, p90 $P90, p99 $P99"
done

a dumpsys meminfo "$PKG" | tr -d '\r' | awk '
  /Java Heap:/ {printf "java heap %d MB, ", $3/1024}
  /Native Heap:/ {printf "native heap %d MB, ", $3/1024}
  /Graphics:/ {printf "graphics %d MB, ", $2/1024}
  /Code:/ {printf "code %d MB, ", $2/1024}
  /TOTAL PSS:/ {printf "total PSS %d MB\n", $3/1024}'
