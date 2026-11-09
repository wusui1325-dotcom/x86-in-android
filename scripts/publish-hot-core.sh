#!/usr/bin/env bash
# Publish a new smokeHot core (wine-launch.sh + optional extras) to a GitHub release
# and print the live manifestUrl. Does NOT bump APK version.
#
# Usage:
#   ./scripts/publish-hot-core.sh [coreVersion] [tag]
# Examples:
#   ./scripts/publish-hot-core.sh 0.1.97+core.hot4 hot-core-0.1.97.1
#   CORE_EXTRA_FILES=hot-core-stamp.txt ./scripts/publish-hot-core.sh 0.1.98+core.1 hot-core-0.1.98.1
#
# Env:
#   REPO=owner/name (default wusui1325-dotcom/x86-in-android)
#   MIN_APK_VC=109701
#   WINE_LAUNCH=path (default app/src/main/assets/engine/wine-launch.sh)
#   CORE_EXTRA_FILES="file1 file2" relative to ROOT or absolute
#   NOTES="..."
#   TARGET_SHA=git sha/ref for release target (default HEAD)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="${REPO:-wusui1325-dotcom/x86-in-android}"
CORE_VERSION="${1:-}"
TAG="${2:-}"
MIN_APK_VC="${MIN_APK_VC:-109701}"
WINE_LAUNCH="${WINE_LAUNCH:-$ROOT/app/src/main/assets/engine/wine-launch.sh}"
TARGET_SHA="${TARGET_SHA:-$(git rev-parse HEAD)}"
CREATED="$(TZ=Asia/Hong_Kong date -Iseconds)"

if [[ -z "$CORE_VERSION" || -z "$TAG" ]]; then
  echo "usage: $0 <coreVersion> <tag>" >&2
  echo "  e.g. $0 0.1.97+core.hot5 hot-core-0.1.97.2" >&2
  exit 2
fi
if [[ ! -f "$WINE_LAUNCH" ]]; then
  echo "missing wine-launch: $WINE_LAUNCH" >&2
  exit 1
fi

STAGE="$(mktemp -d /tmp/hot-core-XXXXXX)"
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

cp "$WINE_LAUNCH" "$STAGE/wine-launch.sh"
FILES_JSON=""
add_file() {
  local path="$1" src="$2"
  local sha size url
  cp "$src" "$STAGE/$(basename "$path")"
  sha=$(sha256sum "$STAGE/$(basename "$path")" | awk '{print $1}')
  size=$(wc -c < "$STAGE/$(basename "$path")" | tr -d ' ')
  url="https://github.com/${REPO}/releases/download/${TAG}/$(basename "$path")"
  if [[ -n "$FILES_JSON" ]]; then FILES_JSON+=","; fi
  FILES_JSON+=$(printf '\n    {"path":"%s","url":"%s","sha256":"%s","size":%s}' \
    "$path" "$url" "$sha" "$size")
  echo "  + $path sha256=$sha size=$size"
}

echo "Staging core $CORE_VERSION → tag $TAG"
add_file "wine-launch.sh" "$STAGE/wine-launch.sh"

# optional stamp / extras
if [[ -n "${CORE_EXTRA_FILES:-}" ]]; then
  for f in $CORE_EXTRA_FILES; do
    if [[ -f "$f" ]]; then src="$f"
    elif [[ -f "$ROOT/$f" ]]; then src="$ROOT/$f"
    else echo "missing extra: $f" >&2; exit 1
    fi
    add_file "$(basename "$src")" "$src"
  done
fi

NOTES="${NOTES:-smokeHot hot-core $CORE_VERSION (mmap32_parent from wine-launch). minApk=$MIN_APK_VC}"
cat > "$STAGE/manifest.json" <<JSON
{
  "schema": 1,
  "coreVersion": "$CORE_VERSION",
  "channel": "smokeHot",
  "createdAt": "$CREATED",
  "notes": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$NOTES"),
  "minApkVersionCode": $MIN_APK_VC,
  "files": [$FILES_JSON
  ],
  "smoke": {
    "workspaceId": "",
    "exeHint": "",
    "waitSeconds": 90
  }
}
JSON

MANIFEST_URL="https://github.com/${REPO}/releases/download/${TAG}/manifest.json"
echo "--- manifest ---"
cat "$STAGE/manifest.json"
echo "----------------"

# Create or update release
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release $TAG exists — uploading/clobbering assets"
  gh release upload "$TAG" \
    "$STAGE/manifest.json" \
    "$STAGE"/* \
    --repo "$REPO" --clobber
else
  echo "Creating release $TAG (target $TARGET_SHA)"
  # upload only unique basenames
  mapfile -t ASSETS < <(find "$STAGE" -maxdepth 1 -type f | sort -u)
  gh release create "$TAG" \
    --repo "$REPO" \
    --target "$TARGET_SHA" \
    --title "hot-core $CORE_VERSION" \
    --notes "$NOTES"$'\n\n'"manifestUrl: $MANIFEST_URL" \
    "${ASSETS[@]}"
fi

echo
echo "OK manifestUrl=$MANIFEST_URL"
echo "coreVersion=$CORE_VERSION"
echo "Bake this URL into assets/hot/smoke-hot-defaults.json + HotSmokeConfig.OFFICIAL_MANIFEST_URL when shipping a new APK;"
echo "or leave APK alone — devices with autoLoop already will pick up the new coreVersion on next poll."
