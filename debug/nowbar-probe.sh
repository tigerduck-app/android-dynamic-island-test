#!/usr/bin/env bash
# Report whether an Android 16 Live Update / Samsung Now Bar chip is actually
# live for this app, and on which surface.
#
# Reads back what the OS did with the posted notification instead of trusting
# that the post succeeded. Nothing here needs root; all of it is dumpsys.
#
# Usage:
#   ./debug/nowbar-probe.sh                 # one-shot report
#   ./debug/nowbar-probe.sh --watch 3       # re-run every 3s (proves the ticker is live)
#   ./debug/nowbar-probe.sh --raw           # also print the raw Samsung list block
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
WATCH=0
RAW="false"

usage() { sed -n '2,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while (( $# )); do
  case "$1" in
    --watch)      WATCH="${2:?--watch needs seconds}"; shift 2 ;;
    --raw)        RAW="true"; shift ;;
    -s|--serial)  SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    -p|--package) PKG="${2:?--package needs a value}";   shift 2 ;;
    -h|--help)    usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

require adb
[[ -n "$SERIAL" ]] || SERIAL="$(pick_device device)"

hdr() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
kv()  { printf '  %-34s %s\n' "$1" "${2:-—}"; }

probe_once() {
  # dumpsys systemui is ~35k lines; take it once per run, not per lookup.
  local sysui notif
  sysui="$(mktemp "${TMPDIR:-/tmp}/sysui.XXXXXX")"
  notif="$(mktemp "${TMPDIR:-/tmp}/notif.XXXXXX")"
  adb -s "$SERIAL" shell dumpsys activity service com.android.systemui >"$sysui" 2>/dev/null || true
  adb -s "$SERIAL" shell dumpsys notification --noredact  >"$notif" 2>/dev/null || true

  local manu model sdk rel oneui
  manu="$(ash "$SERIAL" 'getprop ro.product.manufacturer')"
  model="$(ash "$SERIAL" 'getprop ro.product.model')"
  sdk="$(ash "$SERIAL" 'getprop ro.build.version.sdk')"
  rel="$(ash "$SERIAL" 'getprop ro.build.version.release')"
  oneui="$(ash "$SERIAL" 'getprop ro.build.version.oneui')"

  hdr "Device"
  kv "manufacturer / model" "$manu / $model"
  kv "android" "$rel (SDK $sdk)"
  [[ -n "$oneui" ]] && kv "One UI (raw / decoded)" \
    "$oneui / $((oneui/10000)).$(( (oneui/100)%100 )).$((oneui%100))"

  hdr "Permissions"
  local pkgdump
  pkgdump="$(adb -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' || true)"
  kv "POST_NOTIFICATIONS" \
    "$(grep -oE 'POST_NOTIFICATIONS: granted=[a-z]+' <<<"$pkgdump" | head -1)"
  kv "POST_PROMOTED_NOTIFICATIONS" \
    "$(grep -oE 'POST_PROMOTED_NOTIFICATIONS: granted=[a-z]+' <<<"$pkgdump" | head -1)"

  hdr "Our notification"
  local flags short keys
  flags="$(grep -o "pkg=$PKG.*flags=[A-Za-z_|]*" "$notif" | grep -oE 'flags=[A-Za-z_|]*' | head -1 || true)"
  short="$(grep -oE 'android\.shortCriticalText=String \([^)]*\)' "$notif" | head -1 || true)"
  keys="$(grep -oE 'android\.ongoingActivityNoti\.[A-Za-z.]+' "$notif" | sort -u | tr '\n' ' ' || true)"
  kv "posted" "$( [[ -n "$flags" ]] && echo yes || echo NO )"
  kv "flags" "${flags#flags=}"
  kv "FLAG_PROMOTED_ONGOING" "$( [[ "$flags" == *PROMOTED_ONGOING* ]] && echo yes || echo no )"
  kv "shortCriticalText" "${short##*=String }"
  kv "ongoingActivityNoti keys" "${keys:-none}"

  # --- Samsung lane -------------------------------------------------------
  local lane="" block=""
  if [[ -n "$oneui" ]] || grep -q "OngoingActivityController" "$sysui"; then
    hdr "Samsung Now Bar (即時通知)"
    block="$(sed -n '/Showing list/,/Ongoing Activity History/p' "$sysui" || true)"
    # Which list holds our key? Walk the block and remember the last header seen.
    lane="$(awk -v pkg="$PKG" '
      /Showing list/ { cur="Showing" }
      /Hidden list/  { cur="Hidden" }
      /Pending list/ { cur="Pending" }
      index($0, pkg) { print cur; exit }
    ' <<<"$block")"
    kv "list membership" "${lane:-not in any list}"
    kv "promoted (isPromotedState)" "$(grep -oE 'promoted : [a-z]+' <<<"$block" | head -1 | awk '{print $3}')"
    kv "style" "$(grep -oE 'style : [0-9]+' <<<"$block" | head -1 | awk '{print $3}')"
    kv "now_bar_enabled" "$(ash "$SERIAL" 'settings get system now_bar_enabled')"
    kv "app allowlist key" \
      "$(ash "$SERIAL" "settings get system key_now_bar_$(tr '.' '_' <<<"$PKG")")"
    [[ "$RAW" == "true" ]] && { echo; sed 's/^/    /' <<<"$block"; }
  fi

  # --- AOSP lane ----------------------------------------------------------
  hdr "AOSP Live Updates surfaces"
  kv "AOD interactor isPresent" "$(grep -oE 'isPresent=[a-z]+' "$sysui" | head -1 | cut -d= -f2)"
  kv "showPromotedNotificationsOnAOD" "$(grep -oE 'showPromotedNotificationsOnAOD=[a-z]+' "$sysui" | head -1 | cut -d= -f2)"
  # View visibility is the first flag char of the dump token: V=VISIBLE,
  # G=GONE, I=INVISIBLE. Samsung keeps this GONE even when the feature is on.
  local aodvis
  aodvis="$(grep -m1 'aod_promoted_notification_frame' "$sysui" \
            | grep -oE 'ComposeView\{[0-9a-f]+ [A-Z.]+' | awk '{print $2}' || true)"
  case "${aodvis:0:1}" in
    V) aodvis="$aodvis (VISIBLE)" ;;
    G) aodvis="$aodvis (GONE)" ;;
    I) aodvis="$aodvis (INVISIBLE)" ;;
  esac
  kv "aod_promoted_notification_frame" "$aodvis"
  kv "status-bar chip width" \
    "$(grep -oE 'ongoing_activity_chip_primary\}, w:[0-9]+' "$sysui" | head -1 | cut -d: -f2)"
  kv "mHasPrimaryOngoingActivity" "$(grep -oE 'mHasPrimaryOngoingActivity=[a-z]+' "$sysui" | head -1 | cut -d= -f2)"

  # --- verdict ------------------------------------------------------------
  hdr "Verdict"
  if (( sdk < 36 )); then
    echo "  AOSP Live Updates need API 36; this device is $sdk, so the RON lane"
    echo "  cannot work here (hasPromotableCharacteristics() does not exist)."
    if [[ -n "$lane" ]]; then
      echo "  BUT the Samsung card lane put this app in the '$lane' list —"
      echo "  that is the interesting result on this build. Capture it."
    elif [[ -n "$oneui" ]]; then
      echo "  Samsung device, not in any Now Bar list. Try: ./debug/install.sh --card --home"
    fi
  elif [[ -z "$flags" ]]; then
    echo "  No notification posted. Run ./debug/install.sh --samsung --home"
  elif [[ "$flags" != *PROMOTED_ONGOING* ]]; then
    echo "  Posted, but the OS did NOT set FLAG_PROMOTED_ONGOING — check that the"
    echo "  notification is ongoing, titled, promotable-styled and NOT colorized."
  elif [[ "$lane" == "Showing" ]]; then
    echo "  SHOWING in Samsung's Now Bar. This is the working state."
  elif [[ -n "$lane" ]]; then
    local prom
    prom="$(grep -oE 'promoted : [a-z]+' <<<"$block" | head -1 | awk '{print $3}')"
    echo "  In Samsung's $lane list (promoted=$prom), not Showing."
    if [[ "$prom" == "true" ]]; then
      echo "  promoted=true means the bypass worked — this is a display-state issue,"
      echo "  not a capability one. Showing requires the screen ON, the device"
      echo "  unlocked, and the app backgrounded. Check all three and re-probe."
    else
      echo "  promoted=false means the bypass did NOT take. Send ONLY"
      echo "  android.ongoingActivityNoti.automation=true — any ongoingActivityNoti.style"
      echo "  >= 1 forces mIsRon=false and cancels it."
    fi
  else
    echo "  Promoted by the OS. No Samsung Now Bar on this device, so the AOSP"
    echo "  status-bar chip is the surface to look for (next to the clock)."
  fi

  rm -f "$sysui" "$notif"
}

if (( WATCH > 0 )); then
  while true; do
    clear
    probe_once
    printf '\n  (--watch %ss; ctrl-c to stop)\n' "$WATCH"
    sleep "$WATCH"
  done
else
  probe_once
fi
