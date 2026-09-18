#!/usr/bin/env bash
# Build :app:assembleDebug, install to a chosen device, and launch IslandCheck.
#
# The test modes drive MainActivity's intent extras so a run is reproducible
# without tapping screen coordinates.
#
# Usage:
#   ./debug/install.sh                 # build, install, launch idle
#   ./debug/install.sh --plain         # ...and auto-post a plain AOSP promoted notification
#   ./debug/install.sh --samsung       # ...and auto-post with the Samsung `automation` extra
#                                      #    (RON lane — Android 16+ only)
#   ./debug/install.sh --card          # ...and auto-post with the private-card
#                                      #    extras incl. style=1 (the only lane
#                                      #    that can exist pre-Android-16; use
#                                      #    this on One UI 7 / Android 15)
#   ./debug/install.sh --no-build      # skip gradle, install the existing APK
#   ./debug/install.sh --home          # send HOME after launching (Now Bar hides
#                                      # an entry while its own app is foreground)
#
# Options:
#   -s, --serial SER   target device (default: auto-pick / prompt)
#   -p, --package PKG  app id (default: com.test.island.dynamic.android)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

PKG="$PKG_DEFAULT"
SERIAL=""
MODE=""
BUILD="true"
GO_HOME="false"

usage() { sed -n '2,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while (( $# )); do
  case "$1" in
    --plain)       MODE="auto_plain";   shift ;;
    --samsung)     MODE="auto_samsung"; shift ;;
    --card)        MODE="auto_card";    shift ;;
    --no-build)    BUILD="false";       shift ;;
    --home)        GO_HOME="true";      shift ;;
    -s|--serial)   SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    -p|--package)  PKG="${2:?--package needs a value}";   shift 2 ;;
    -h|--help)     usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

cd "$ROOT_DIR"
require adb

[[ -n "$SERIAL" ]] || SERIAL="$(pick_device device)"

if [[ "$BUILD" == "true" ]]; then
  echo "==> Building :app:assembleDebug"
  ./gradlew :app:assembleDebug
fi

apk="$(resolve_apk "$ROOT_DIR/app/build/outputs/apk/debug/app-debug*.apk")"
echo "==> Installing $apk → $SERIAL"
adb_install "$SERIAL" "$PKG" "$apk"

# POST_NOTIFICATIONS is runtime-gated since API 33 and the whole harness is
# silently useless without it. Granting here beats discovering it later.
if [[ "$(ash "$SERIAL" "getprop ro.build.version.sdk")" -ge 33 ]]; then
  adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null \
    && echo "==> Granted POST_NOTIFICATIONS" \
    || echo "warn: could not grant POST_NOTIFICATIONS (already granted, or denied by policy)" >&2
fi

# Always start clean: a stale ongoing notification from a previous run would be
# indistinguishable from a fresh one in the dumps.
adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null
sleep 1

if [[ -n "$MODE" ]]; then
  echo "==> Launching with $MODE=true"
  adb -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" --ez "$MODE" true >/dev/null
else
  echo "==> Launching"
  adb -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" >/dev/null
fi

if [[ "$GO_HOME" == "true" ]]; then
  sleep 4
  echo "==> HOME (an entry stays Pending while its own app is foreground)"
  adb -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null
fi

echo "==> Done. Probe with: ./debug/nowbar-probe.sh -s $SERIAL"
