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

MODEL="${1:-small}"
SOURCE="$MODELS_DIR/$MODEL"

[[ -d "$SOURCE" ]] || die "Model '$MODEL' not in .models/. Run: scripts/fetch-deps.sh $MODEL"
[[ -f "$MODELS_DIR/silero_vad.onnx" ]] || die "silero_vad.onnx missing. Run: scripts/fetch-deps.sh"

# Only one model at a time: two would double an already large APK, and
# ModelStore extracts whatever is present.
info "Staging '$MODEL' into the bundled flavor"
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR/$MODEL"

cp "$MODELS_DIR/silero_vad.onnx" "$ASSETS_DIR/silero_vad.onnx"
cp "$SOURCE/$MODEL-encoder.int8.onnx" "$ASSETS_DIR/$MODEL/"
cp "$SOURCE/$MODEL-decoder.int8.onnx" "$ASSETS_DIR/$MODEL/"
cp "$SOURCE/$MODEL-tokens.txt" "$ASSETS_DIR/$MODEL/"

info "Staged $(du -sh "$ASSETS_DIR" | cut -f1). Now build:"
echo "  ./gradlew assembleBundledRelease"
echo
echo "Note: the app's default model is 'small' (AppSettings.DEFAULT_MODEL)."
if [[ "$MODEL" != "small" ]]; then
    printf '\033[1;33mwarning:\033[0m staged "%s" but the app asks for "small" by default;\n' "$MODEL"
    printf '         change it in Settings on first run, or update DEFAULT_MODEL.\n'
fi
