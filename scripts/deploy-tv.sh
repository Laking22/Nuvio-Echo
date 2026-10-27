#!/usr/bin/env bash
# Build the optimized beta, install it on an Android TV over ADB and compile it ahead of time.
#
# Usage: scripts/deploy-tv.sh <tv-ip:port> [--skip-build]
#
# The ahead-of-time compile is what makes the UI as smooth as a Play Store install: a sideloaded
# APK otherwise starts out interpreted/JIT-compiled until the TV's idle-time optimizer gets to it.

set -euo pipefail

TARGET="${1:?usage: scripts/deploy-tv.sh <tv-ip:port> [--skip-build]}"
SKIP_BUILD="${2:-}"
PACKAGE="com.nuviobeta.tv"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

adb connect "$TARGET" >/dev/null
ABI="$(adb -s "$TARGET" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$ABI" in
  arm64-v8a) APK_ABI="arm64-v8a" ;;
  armeabi-v7a|armeabi) APK_ABI="armeabi-v7a" ;;
  x86_64) APK_ABI="x86_64" ;;
  x86) APK_ABI="x86" ;;
  *) echo "Unknown ABI: $ABI" >&2; exit 1 ;;
esac

if [[ "$SKIP_BUILD" != "--skip-build" ]]; then
  (cd "$ROOT" && ./gradlew :app:assembleFullBeta --console=plain -q)
fi

APK="$ROOT/app/build/outputs/apk/full/beta/app-full-$APK_ABI-beta.apk"
echo "Installing $(basename "$APK") on $TARGET ($ABI)"
adb -s "$TARGET" install -r "$APK"

echo "Compiling ahead of time (about a minute on a TV box)"
adb -s "$TARGET" shell am force-stop "$PACKAGE"
adb -s "$TARGET" shell cmd package compile -f -m speed "$PACKAGE"
echo "Done."
