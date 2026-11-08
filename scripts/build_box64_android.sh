#!/usr/bin/env bash
# 以 NDK 編 box64（Android arm64）並放入 app assets。
# 需要：ANDROID_HOME、ndk 27+、cmake 3.22.1（SDK 內）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"
NDK="${NDK:-$ANDROID_HOME/ndk/27.0.12077973}"
CMAKE="${CMAKE:-$ANDROID_HOME/cmake/3.22.1/bin/cmake}"
NINJA="${NINJA:-$ANDROID_HOME/cmake/3.22.1/bin/ninja}"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
VENDOR="${VENDOR_BOX64:-/workspace/vendor/box64}"
BUILD="${BOX64_BUILD_DIR:-/workspace/vendor/box64-build-api28}"
TAG="${BOX64_TAG:-v0.4.4}"

if [[ ! -d "$VENDOR/.git" ]]; then
  git clone --depth 1 --branch "$TAG" https://github.com/ptitSeb/box64.git "$VENDOR"
fi

mkdir -p "$BUILD"
cd "$BUILD"
"$CMAKE" "$VENDOR" \
  -GNinja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DARM64=1 -DANDROID=1 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static
"$CMAKE" --build . -j"$(nproc)"

mkdir -p "$ROOT/app/src/main/assets/engine"
"$STRIP" --strip-unneeded -o "$ROOT/app/src/main/assets/engine/box64" "$BUILD/box64"
chmod +x "$ROOT/app/src/main/assets/engine/box64"
ls -lh "$ROOT/app/src/main/assets/engine/box64"
# 產出需 ARM_DYNAREC=ON（CMake 在 ARM64=ON 時預設開啟）。效能目標：同機原生 arm64 ≥70%。
