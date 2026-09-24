#!/usr/bin/env bash
# Build libLiteRtDispatch_GoogleTensor.so, the vendor library LiteRT needs to run models on the
# Tensor TPU, and stage it in the app's jniLibs. Google does not publish it prebuilt for current
# LiteRT releases, so it is built from the exact LiteRT revision LiteRT-LM 0.17.0 pins (see
# LITERT_REF in https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.0/WORKSPACE).
#
# Requires: bazelisk (or bazel matching LiteRT's .bazelversion), Android NDK r28b+, curl, python3.
# Usage: ANDROID_NDK_HOME=/path/to/ndk scripts/build-tensor-dispatch.sh
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28b or newer}"

LITERT_REF=9fe5be45564c868408e6514c8aabb83e211a0911
LITERT_SHA256=5dbb113744e103f899c7b1b7c5479126b36a0b7414c3d971185c1f02041bfa39

repo=$(cd "$(dirname "$0")/.." && pwd)
cache="$repo/build/litert-src"
archive="$cache/litert-$LITERT_REF.tar.gz"
out="$repo/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$cache" "$out"
if [[ ! -f "$archive" ]]; then
    curl --fail --location --output "$archive" \
        "https://github.com/google-ai-edge/LiteRT/archive/$LITERT_REF.tar.gz"
fi
echo "$LITERT_SHA256  $archive" | sha256sum --check --quiet
if [[ ! -d "$cache/LiteRT-$LITERT_REF" ]]; then
    tar -xzf "$archive" -C "$cache"
fi

cd "$cache/LiteRT-$LITERT_REF"
"${BAZEL:-bazelisk}" build --config=android_arm64 //litert/vendors/google_tensor/dispatch:dispatch_api_so

cp bazel-bin/litert/vendors/google_tensor/dispatch/libLiteRtDispatch_GoogleTensor.so "$out/"
echo "Staged $out/libLiteRtDispatch_GoogleTensor.so"
