#!/usr/bin/env bash
# 一鍵：bump → assembleRelease
# 跳過 bump：SKIP_BUMP=1 ./scripts/release.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/workspace/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -f /workspace/.secrets/x86-in-android/keystore.properties ]]; then
  export X86_KEYSTORE_PROPS="${X86_KEYSTORE_PROPS:-/workspace/.secrets/x86-in-android/keystore.properties}"
fi

# local.properties for SDK
if [[ ! -f local.properties ]]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

if [[ "${SKIP_BUMP:-0}" != "1" ]]; then
  "$ROOT/scripts/bump_version.sh"
fi

./gradlew assembleRelease --no-daemon "$@"

VER=$(python3 -c 'import json;print(json.load(open("version.json"))["versionName"])')
APK="$ROOT/app/build/outputs/apk/release/x86-in-android-${VER}.apk"
if [[ ! -f "$APK" ]]; then
  APK="$ROOT/app/build/outputs/apk/release/x86-in-android-release.apk"
fi
if [[ -f "$APK" ]]; then
  echo "OK: $APK"
  ls -lh "$APK"
else
  find "$ROOT/app/build/outputs/apk/release" -name '*.apk' -print -exec ls -lh {} \;
fi

python3 - <<'PY'
import json
d=json.load(open("version.json"))
print(f"versionName={d['versionName']} versionCode={d['versionCode']}")
PY
