#!/usr/bin/env bash
# Drive a Samsung Remote Test Lab device over Remote Debug Bridge, and survive
# the tunnel dropping.
#
# RTL sessions are time-limited and idle-sensitive, and any `adb kill-server`
# (Android Studio does this on startup) kills the transport. So: do the whole
# test in one pass, reconnect automatically between steps, and keep the tunnel
# warm while you look at the phone.
#
# Usage:
#   ./debug/rtl.sh 12345                  # port from the RTL client; runs --card
#   ./debug/rtl.sh localhost:12345 --samsung
#   ./debug/rtl.sh 12345 --keepalive      # just hold the tunnel open, no test
#
# Options:
#   --card | --samsung | --plain   which lane to post (default: --card)
#   --keepalive                    skip the test; ping every 20s to stop idle drop
#   --no-build                     don't rebuild the APK first

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

usage() { sed -n '2,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

TARGET=""
LANE="--card"
KEEPALIVE="false"
BUILD_FLAG=""

while (( $# )); do
  case "$1" in
    --card|--samsung|--plain) LANE="$1"; shift ;;
    --keepalive)  KEEPALIVE="true"; shift ;;
    --no-build)   BUILD_FLAG="--no-build"; shift ;;
    -h|--help)    usage 0 ;;
    -*)           echo "unknown arg: $1" >&2; usage 1 ;;
    *)            TARGET="$1"; shift ;;
  esac
done

[[ -n "$TARGET" ]] || { echo "error: need the RTL port (see the RTL client)" >&2; usage 1; }
# A bare number is the common case — the RTL client shows "adb connect localhost:PORT".
[[ "$TARGET" =~ ^[0-9]+$ ]] && TARGET="localhost:$TARGET"

require adb
cd "$ROOT_DIR"

# Reconnect until the transport answers. `adb connect` alone lies: it reports
# success for a socket that is already half-dead, so verify with a real command.
rtl_connect() {
  local tries="${1:-10}" i
  for (( i = 1; i <= tries; i++ )); do
    adb connect "$TARGET" >/dev/null 2>&1 || true
    if run_with_timeout 8 adb -s "$TARGET" shell true >/dev/null 2>&1; then
      return 0
    fi
    echo "   …transport not ready (try $i/$tries); reconnecting" >&2
    adb disconnect "$TARGET" >/dev/null 2>&1 || true
    sleep 3
  done
  return 1
}

# Run a command, reconnecting once if the transport died mid-step.
with_reconnect() {
  if "$@"; then return 0; fi
  echo "   …step failed; reconnecting and retrying once" >&2
  rtl_connect 10 || { echo "error: cannot reach $TARGET" >&2; return 1; }
  "$@"
}

echo "==> Connecting to $TARGET"
rtl_connect 10 || { echo "error: could not establish RDB transport to $TARGET" >&2; exit 1; }

model="$(ash "$TARGET" 'getprop ro.product.model')"
sdk="$(ash "$TARGET" 'getprop ro.build.version.sdk')"
oneui="$(ash "$TARGET" 'getprop ro.build.version.oneui')"
echo "==> Connected: ${model:-?}  API ${sdk:-?}  One UI ${oneui:-n/a}"

if [[ "$KEEPALIVE" == "true" ]]; then
  echo "==> Keepalive: pinging every 20s. Ctrl-C to stop."
  while true; do
    if ! run_with_timeout 8 adb -s "$TARGET" shell true >/dev/null 2>&1; then
      echo "   …dropped; reconnecting"
      rtl_connect 10 || echo "   …still down" >&2
    fi
    sleep 20
  done
fi

# One pass, so a mid-session drop costs a rerun rather than a lost session.
echo "==> Installing + posting ($LANE)"
with_reconnect "$SCRIPT_DIR/install.sh" $BUILD_FLAG "$LANE" --home -s "$TARGET"

echo "==> Probing"
with_reconnect "$SCRIPT_DIR/nowbar-probe.sh" -s "$TARGET"

cat <<NOTE

Reminder for this device:
  * Showing requires screen ON + UNLOCKED + app backgrounded. RTL devices often
    sit locked — unlock in the RTL window, then re-run:
        ./debug/nowbar-probe.sh -s $TARGET
  * If the tunnel keeps dropping, hold it open in another terminal:
        ./debug/rtl.sh ${TARGET##*:} --keepalive
NOTE
