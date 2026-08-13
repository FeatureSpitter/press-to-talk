#!/usr/bin/env bash
#
# Stages model files into the bundled flavor's assets, so `assembleBundledRelease`
# produces a self-contained APK you can hand to someone.
#
# The models are gitignored - this copies them in from .models/ on demand rather
# than keeping ~375 MB in the source tree.
#
# Usage:
#   scripts/stage-bundled.sh           # stage the default model (small)
#   scripts/stage-bundled.sh base      # stage a different one
#   scripts/stage-bundled.sh --clear   # remove staged assets

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT/.models"
ASSETS_DIR="$ROOT/app/src/bundled/assets/models"

info() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

if [[ "${1:-}" == "--clear" ]]; then
    info "Clearing staged assets"
    rm -rf "$ASSETS_DIR"
    exit 0
fi

[[ -f "$MODELS_DIR/silero_vad.onnx" ]] || die "silero_vad.onnx missing. Run: scripts/fetch-deps.sh"

# All three by default, so the Settings picker is a real choice on any phone
# rather than a list of things that are not installed.
models=("$@")
if [[ ${#models[@]} -eq 0 ]]; then
    models=(tiny base small)
fi

rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"
cp "$MODELS_DIR/silero_vad.onnx" "$ASSETS_DIR/silero_vad.onnx"

for model in "${models[@]}"; do
    source_dir="$MODELS_DIR/$model"
    [[ -d "$source_dir" ]] || die "Model '$model' not in .models/. Run: scripts/fetch-deps.sh $model"

    info "Staging '$model'"
    mkdir -p "$ASSETS_DIR/$model"
    cp "$source_dir/$model-encoder.int8.onnx" "$ASSETS_DIR/$model/"
    cp "$source_dir/$model-decoder.int8.onnx" "$ASSETS_DIR/$model/"
    cp "$source_dir/$model-tokens.txt" "$ASSETS_DIR/$model/"
done

DEFAULT_MODEL="small"   # keep in step with AppSettings.DEFAULT_MODEL
if [[ ! " ${models[*]} " =~ " $DEFAULT_MODEL " ]]; then
    printf '\033[1;33mwarning:\033[0m the app defaults to "%s", which is not staged.\n' "$DEFAULT_MODEL"
    printf '         It will fall back to whichever model is present on first run.\n'
fi

info "Staged $(du -sh "$ASSETS_DIR" | cut -f1) (${models[*]}). Now build:"
echo "  ./gradlew assembleBundledRelease"
