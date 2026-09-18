#!/usr/bin/env bash
# Shared helpers for debug/*.sh. Source, don't execute.
#
# Trimmed from tigerduck-app-android/debug/_lib.sh. This project is a single
# module with a single variant, so the build-output redirect and the
# debug-clock helpers are omitted; device picking and install retry are kept
# because both matter here (wireless debugging + emulator side by side).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PKG_DEFAULT="com.test.island.dynamic.android"
ACTIVITY=".MainActivity"

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: '$1' not found on PATH" >&2
    exit 1
  }
}

# Run "$@", killing it after <secs>. Returns the command's exit status, or 124
# if it had to be killed. macOS ships no `timeout`, hence the watchdog fallback.
run_with_timeout() {
  local secs="$1"; shift
  local tool
  if tool="$(command -v timeout || command -v gtimeout || true)" && [[ -n "$tool" ]]; then
    "$tool" "$secs" "$@"
    return $?
  fi
  local marker
  marker="$(mktemp "${TMPDIR:-/tmp}/rwt.XXXXXX")"
  "$@" &
  local cmd_pid=$!
  ( sleep "$secs"; printf fired >"$marker"; kill -TERM "$cmd_pid" 2>/dev/null ) &
  local watch_pid=$!
  local rc=0
  wait "$cmd_pid" 2>/dev/null || rc=$?
  kill -TERM "$watch_pid" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true
  [[ -s "$marker" ]] && rc=124
  rm -f "$marker"
  return "$rc"
}

# Echo a serial for an interactive device pick. Returns the only device without
# prompting when just one is connected.
#
# The greedy (.+) before the state column is load-bearing: an mDNS wireless
# serial can itself contain a space — "adb-SERIAL (2)._adb-tls-connect._tcp" —
# and a [^space]+ token would silently drop that device.
pick_device() {
  local label="${1:-device}"
  require adb

  local serials=() labels=()
  # fd 3 so the inner `adb shell` probes don't drain the device list.
  while IFS= read -r line <&3; do
    [[ "$line" =~ ^(.+)[[:space:]]+device([[:space:]].*)?$ ]] || continue
    local serial="${BASH_REMATCH[1]}"
    local model="" sdk="" tag="" probe
    # A stale wireless transport still listed as `device` makes adb shell hang
    # forever; cap it and tag rather than freeze the picker.
    if probe="$(run_with_timeout 5 adb -s "$serial" shell \
        'getprop ro.product.model; getprop ro.build.version.sdk' \
        </dev/null 2>/dev/null)"; then
      probe="${probe//$'\r'/}"
      { IFS= read -r model; IFS= read -r sdk; } <<< "$probe" || true
    else
      tag=" [unresponsive]"
    fi
    [[ "$serial" == emulator-* ]] && tag="$tag [emulator]"
    serials+=("$serial")
    labels+=("${model:-?}  API ${sdk:-?}$tag   ($serial)")
  done 3< <(adb devices)

  if [[ ${#serials[@]} -eq 0 ]]; then
    echo "error: no connected adb $label. Run 'adb devices' to check." >&2
    return 1
  fi
  if [[ ${#serials[@]} -eq 1 ]]; then
    echo "${serials[0]}"
    return 0
  fi

  echo "Pick $label:" >&2
  local i
  for i in "${!labels[@]}"; do
    echo "  [$i] ${labels[$i]}" >&2
  done
  local choice
  while true; do
    read -r -p "> " choice
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 0 && choice < ${#serials[@]} )); then
      echo "${serials[$choice]}"
      return 0
    fi
    echo "invalid choice; enter a number 0..$((${#serials[@]} - 1))" >&2
  done
}

prompt_yn() {
  local q="$1" default="${2:-n}" reply p
  case "$default" in
    y|Y) p="$q [Y/n] " ;;
    *)   p="$q [y/N] " ;;
  esac
  read -r -p "$p" reply || true
  reply="${reply:-$default}"
  [[ "$reply" =~ ^[Yy] ]]
}

# adb install with retry-after-uninstall on signature mismatch.
adb_install() {
  local serial="$1" pkg="$2" apk="$3"
  local out rc
  set +e
  out="$(adb -s "$serial" install -r -d "$apk" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "$out"
  if (( rc == 0 )) && [[ "$out" != *"Failure"* ]]; then
    return 0
  fi
  if [[ "$out" == *"signatures do not match"* || "$out" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    if prompt_yn "Signature mismatch on $serial. Uninstall $pkg and reinstall? (user data wiped)" n; then
      adb -s "$serial" uninstall "$pkg"
      adb -s "$serial" install -r -d "$apk"
      return $?
    fi
  fi
  return "$(( rc == 0 ? 1 : rc ))"
}

# Resolve a single APK from a glob. Errors on zero or ambiguous matches.
resolve_apk() {
  local pattern="$1"
  local matches=( $pattern )
  if [[ ${#matches[@]} -eq 0 || ! -f "${matches[0]}" ]]; then
    echo "error: no APK matched $pattern" >&2
    return 1
  fi
  if [[ ${#matches[@]} -gt 1 ]]; then
    echo "error: multiple APKs matched $pattern:" >&2
    printf '  %s\n' "${matches[@]}" >&2
    return 1
  fi
  echo "${matches[0]}"
}

# adb shell with \r stripped — CRLF from adb breaks string comparisons.
ash() {
  local serial="$1"; shift
  adb -s "$serial" shell "$@" 2>/dev/null | tr -d '\r'
}
