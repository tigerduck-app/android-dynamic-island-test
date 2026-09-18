#!/usr/bin/env bash
# Build the debug APK and stage it in debug/apk/ for upload to Firebase Test Lab.
#
# The copy is named islandcheck-<versionName>-<gitsha>[-dirty]-<UTC>.apk so a
# Test Lab result can always be traced back to an exact tree. Test Lab is the
# practical way to answer "does this work on every Samsung?" — the recipe is
# verified on exactly one device, and the Now Bar behaviour is undocumented.
#
# Usage:
#   ./debug/build-apk.sh              # build, stage, print the upload command
#   ./debug/build-apk.sh --no-build   # stage the APK already in app/build/
#   ./debug/build-apk.sh --clean      # delete older staged APKs first
#
# Options:
#   -o, --out DIR   staging dir (default: debug/apk)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

OUT_DIR="$ROOT_DIR/debug/apk"
BUILD="true"
CLEAN="false"

usage() { sed -n '2,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while (( $# )); do
  case "$1" in
    --no-build)  BUILD="false"; shift ;;
    --clean)     CLEAN="true";  shift ;;
    -o|--out)    OUT_DIR="${2:?--out needs a value}"; shift 2 ;;
    -h|--help)   usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

cd "$ROOT_DIR"

if [[ "$BUILD" == "true" ]]; then
  echo "==> Building :app:assembleDebug"
  ./gradlew :app:assembleDebug
fi

src="$(resolve_apk "$ROOT_DIR/app/build/outputs/apk/debug/app-debug*.apk")"

# versionName lives in app/build.gradle.kts; read it rather than hardcoding so
# the staged filename tracks it automatically.
version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' \
            app/build.gradle.kts | head -1)"
version="${version:-unknown}"

sha="nogit"
if git rev-parse --git-dir >/dev/null 2>&1; then
  sha="$(git rev-parse --short HEAD 2>/dev/null || echo untracked)"
  # A dirty tree means the APK does not correspond to any commit — say so in
  # the filename instead of letting a Test Lab result look reproducible.
  [[ -n "$(git status --porcelain 2>/dev/null)" ]] && sha="$sha-dirty"
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="islandcheck-${version}-${sha}-${stamp}.apk"

mkdir -p "$OUT_DIR"
if [[ "$CLEAN" == "true" ]]; then
  echo "==> Removing older staged APKs in $OUT_DIR"
  rm -f "$OUT_DIR"/islandcheck-*.apk
fi

cp "$src" "$OUT_DIR/$name"

size="$(du -h "$OUT_DIR/$name" | cut -f1 | tr -d ' ')"
digest="$(shasum -a 256 "$OUT_DIR/$name" | cut -d' ' -f1)"

echo
echo "==> Staged: debug/apk/$name"
echo "    size    $size"
echo "    sha256  $digest"

cat <<HINT

Upload to Firebase Test Lab (Robo test across several devices):

  gcloud firebase test android run \\
    --type robo \\
    --app "debug/apk/$name" \\
    --device model=<MODEL>,version=<API>,locale=en,orientation=portrait \\
    --device model=<MODEL2>,version=<API2>,locale=en,orientation=portrait \\
    --timeout 3m

List the real model ids first — they are not guessable, and Live Updates need
API 36+, so filter on that:

  gcloud firebase test android models list --filter="supportedVersionIds:36"

Two caveats for this app specifically:

  * POST_NOTIFICATIONS is a runtime permission. A Robo test has to tap the
    grant dialog itself, so confirm it did before trusting a negative result.
  * Robo cannot pass the --ez auto_samsung / auto_plain intent extras that
    ./debug/install.sh uses, so it will exercise the on-screen buttons instead.
    Check the Robo crawl graph to confirm it actually reached section 3/4.
HINT
