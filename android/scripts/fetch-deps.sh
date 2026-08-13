#!/usr/bin/env bash
#
# Downloads the sherpa-onnx Android runtime and Whisper/VAD model files.
#
# Nothing this script fetches is committed to git - the AAR is ~49 MB and the
# models run to hundreds of MB each. Run it once after cloning.
#
# Usage:
#   scripts/fetch-deps.sh                  # AAR + VAD + tiny, base, small (for benchmarking)
#   scripts/fetch-deps.sh small            # AAR + VAD + just the small model
#   scripts/fetch-deps.sh --aar-only       # runtime only, no models
#
# Downloaded models land in android/.models/<name>/ containing only the int8
# encoder, int8 decoder and tokens file. The fp32 variants and test wavs are
# deleted after extraction: fp32 is unusable on a phone, and for the larger
# models it is split across external .weights sidecar files.

set -euo pipefail

SHERPA_VERSION="1.13.5"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$ROOT/app/libs"
MODELS_DIR="$ROOT/.models"
CACHE_DIR="$ROOT/.cache"

RELEASES="https://github.com/k2-fsa/sherpa-onnx/releases/download"
AAR_URL="$RELEASES/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
ASR_MODELS="$RELEASES/asr-models"

info() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

for tool in curl tar bzip2; do
    command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required but not installed."
done

# curl -C - resumes a partial download, so an interrupted run costs nothing.
download() {
    local url="$1" dest="$2"
    if [[ -s "$dest" ]]; then
        info "Already have $(basename "$dest") - skipping"
        return
    fi
    info "Downloading $(basename "$dest")"
    curl -# -L -C - -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
}

fetch_model() {
    local name="$1"
    local dir="$MODELS_DIR/$name"
    local encoder="$dir/$name-encoder.int8.onnx"

    if [[ -s "$encoder" ]]; then
        info "Model '$name' already extracted - skipping"
        return
    fi

    local tarball="$CACHE_DIR/sherpa-onnx-whisper-$name.tar.bz2"
    download "$ASR_MODELS/sherpa-onnx-whisper-$name.tar.bz2" "$tarball"

    info "Extracting '$name'"
    rm -rf "$dir"
    mkdir -p "$dir"
    # The tarball has a sherpa-onnx-whisper-<name>/ prefix; strip it.
    tar -xjf "$tarball" -C "$dir" --strip-components=1

    # Keep only what the app loads. Everything else is dead weight.
    find "$dir" -mindepth 1 \
        ! -name "$name-encoder.int8.onnx" \
        ! -name "$name-decoder.int8.onnx" \
        ! -name "$name-tokens.txt" \
        -delete 2>/dev/null || true

    for required in "$name-encoder.int8.onnx" "$name-decoder.int8.onnx" "$name-tokens.txt"; do
        [[ -s "$dir/$required" ]] || die "Expected '$required' in the '$name' tarball but it is missing."
    done

    info "Model '$name' ready ($(du -sh "$dir" | cut -f1))"
}

mkdir -p "$LIBS_DIR" "$MODELS_DIR" "$CACHE_DIR"

# --- runtime ---------------------------------------------------------------
download "$AAR_URL" "$LIBS_DIR/sherpa-onnx-$SHERPA_VERSION.aar"

# Stale AARs from a previous version would be picked up by the fileTree
# dependency and collide with the current one.
find "$LIBS_DIR" -name 'sherpa-onnx-*.aar' ! -name "sherpa-onnx-$SHERPA_VERSION.aar" -print -delete

if [[ "${1:-}" == "--aar-only" ]]; then
    info "Runtime only, as requested. Done."
    exit 0
fi

# --- voice activity detection ----------------------------------------------
# Required in every build: Whisper cannot see past 30s, so VAD does the splitting.
download "$ASR_MODELS/silero_vad.onnx" "$MODELS_DIR/silero_vad.onnx"

# --- speech recognition models ---------------------------------------------
models=("$@")
if [[ ${#models[@]} -eq 0 ]]; then
    models=(tiny base small)
    warn "Fetching tiny, base and small for benchmarking (~1.5 GB). Pass a name to fetch just one."
fi

for model in "${models[@]}"; do
    case "$model" in
        tiny|base|small|medium|turbo) fetch_model "$model" ;;
        *) die "Unknown model '$model'. Expected one of: tiny, base, small, medium, turbo." ;;
    esac
done

info "Done. Runtime in app/libs/, models in .models/"
du -sh "$MODELS_DIR"/* 2>/dev/null || true
