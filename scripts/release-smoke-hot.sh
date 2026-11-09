#!/usr/bin/env bash
# Build signed smokeHot release APK (does NOT bump mainline version.json).
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
if [[ ! -f local.properties ]]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

# Bake tip SHA into BuildConfig via override (commit happens after build in tip flow;
# for release-after-commit, export GIT_SHA_OVERRIDE=$(git rev-parse --short HEAD))
export GIT_SHA_OVERRIDE="${GIT_SHA_OVERRIDE:-$(git rev-parse --short HEAD)}"

./gradlew assembleSmokeHotRelease --no-daemon "$@"

VER="0.1.96-hot.3"
APK="$ROOT/app/build/outputs/apk/smokeHot/release/x86-in-android-${VER}.apk"
if [[ ! -f "$APK" ]]; then
  APK=$(find "$ROOT/app/build/outputs/apk/smokeHot/release" -name '*.apk' | head -1 || true)
fi
if [[ -f "$APK" ]]; then
  echo "OK: $APK"
  ls -lh "$APK"
  sha256sum "$APK"
else
  echo "APK not found" >&2
  find "$ROOT/app/build/outputs/apk" -name '*.apk' -print || true
  exit 1
fi
