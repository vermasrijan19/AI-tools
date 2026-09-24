#!/usr/bin/env bash
# Push Gemma 4 .litertlm model files to the connected phone, where the app looks for them.
#
# Usage: scripts/push-model.sh path/to/gemma-4-E2B-it_Google_Tensor_G6.litertlm [more.litertlm ...]
set -euo pipefail

DEVICE_DIR=/data/local/tmp/llm

if [[ $# -eq 0 ]]; then
    echo "usage: $0 MODEL.litertlm [MODEL.litertlm ...]" >&2
    exit 1
fi

adb shell mkdir -p "$DEVICE_DIR"
for model in "$@"; do
    name=$(basename "$model")
    echo "Pushing $name to $DEVICE_DIR ..."
    adb push "$model" "$DEVICE_DIR/$name"
    adb shell chmod 644 "$DEVICE_DIR/$name"
done
adb shell ls -l "$DEVICE_DIR"
