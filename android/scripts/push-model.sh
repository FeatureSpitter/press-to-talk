#!/usr/bin/env bash
#
# Pushes model files to the dev build on a connected device.
#
# The dev flavor ships no models so the APK stays small and reinstalls stay
# fast. Models live in the app's external files directory, which adb can write
# to directly - unlike filesDir, which needs run-as gymnastics.
#
# Usage:
#   scripts/push-model.sh              # push every model in .models/
#   scripts/push-model.sh small        # push just one
#   scripts/push-model.sh --list       # show what is already on the device

set -euo pipefail

PACKAGE="com.presstotalk.mobile.dev"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT/.models"
REMOTE="/sdcard/Android/data/$PACKAGE/files/models"

info() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || die "adb not found on PATH."

case "$(adb get-state 2>/dev/null || echo none)" in
    device) ;;
    unauthorized) die "Device is unauthorized. Accept the RSA prompt on the phone." ;;
    *) die "No device. Connect over USB with debugging enabled, then check 'adb devices'." ;;
esac

if [[ "${1:-}" == "--list" ]]; then
    info "Models on device"
    adb shell "ls -la $REMOTE $REMOTE/*/ 2>/dev/null" || echo "(nothing pushed yet)"
    exit 0
fi

[[ -d "$MODELS_DIR" ]] || die "No .models/ directory. Run scripts/fetch-deps.sh first."

adb shell "mkdir -p $REMOTE"

# The VAD is needed by every build - Whisper cannot handle long audio without it.
if [[ -f "$MODELS_DIR/silero_vad.onnx" ]]; then
    info "Pushing silero_vad.onnx"
    adb push "$MODELS_DIR/silero_vad.onnx" "$REMOTE/silero_vad.onnx"
else
    die "silero_vad.onnx missing. Run scripts/fetch-deps.sh."
fi

models=("$@")
if [[ ${#models[@]} -eq 0 ]]; then
    mapfile -t models < <(find "$MODELS_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)
    [[ ${#models[@]} -gt 0 ]] || die "No models in $MODELS_DIR. Run scripts/fetch-deps.sh."
fi

for model in "${models[@]}"; do
    local_dir="$MODELS_DIR/$model"
    [[ -d "$local_dir" ]] || die "Model '$model' not in $MODELS_DIR. Fetch it first."

    info "Pushing '$model' ($(du -sh "$local_dir" | cut -f1))"
    adb shell "mkdir -p $REMOTE/$model"
    for file in "$local_dir"/*; do
        adb push "$file" "$REMOTE/$model/$(basename "$file")"
    done
done

# Directories created by `adb shell mkdir` are owned by shell with mode 770, so
# the app cannot even traverse into its own external files directory - every
# model file reads back as "absent". Grant traversal and read to everyone.
info "Fixing permissions so the app can read what shell just wrote"
adb shell "chmod -R a+rX $REMOTE"

info "Done. On device:"
adb shell "du -sh $REMOTE/* 2>/dev/null"
