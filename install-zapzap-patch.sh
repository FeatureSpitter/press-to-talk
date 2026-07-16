#!/usr/bin/env bash
# Install Press-to-Talk voice transcription patch into the ZapZap Flatpak.
#
# Usage:
#   ./install-zapzap-patch.sh
#
# Requires sudo (to copy files into flatpak). Re-run after every ZapZap update.

set -euo pipefail

APP_ID="com.rtosta.zapzap"
PATCH_DIR="$(cd "$(dirname "$0")/zapzap-patch" && pwd)"

if ! command -v flatpak >/dev/null 2>&1; then
    echo "flatpak not found. Install ZapZap first." >&2
    exit 1
fi

if ! flatpak info "$APP_ID" >/dev/null 2>&1; then
    echo "ZapZap ($APP_ID) is not installed." >&2
    echo "Install with: flatpak install flathub $APP_ID" >&2
    exit 1
fi

ZAPZAP_ROOT="$(flatpak info --show-location "$APP_ID")/files/lib/python3.13/site-packages/zapzap"

if [[ ! -d "$ZAPZAP_ROOT/webengine" ]]; then
    echo "Unexpected ZapZap layout at $ZAPZAP_ROOT" >&2
    exit 1
fi

PTT_DIR="${PRESS_TO_TALK_DIR:-$HOME/projectos/press-to-talk}"
if [[ ! -f "$PTT_DIR/press_to_talk.py" ]]; then
    echo "press-to-talk not found at $PTT_DIR" >&2
    echo "Clone/install it first, or set PRESS_TO_TALK_DIR." >&2
    exit 1
fi

if [[ ! -x "$PTT_DIR/.venv/bin/python" ]]; then
    echo "Python venv missing at $PTT_DIR/.venv — run ./install.sh in press-to-talk first." >&2
    exit 1
fi

# Grant ZapZap permission to call host processes (for GPU transcription)
echo "Granting flatpak-spawn --host permission…"
flatpak override --user --talk-name=org.freedesktop.Flatpak "$APP_ID"

echo "Installing patch into $ZAPZAP_ROOT"
echo "Press-to-Talk dir: $PTT_DIR"

sudo cp "$PATCH_DIR/zapzap/services/VoiceTranscriptionService.py" \
    "$ZAPZAP_ROOT/services/VoiceTranscriptionService.py"
sudo cp "$PATCH_DIR/zapzap/webengine/voice_transcription.js" \
    "$ZAPZAP_ROOT/webengine/voice_transcription.js"
sudo cp "$PATCH_DIR/zapzap/webengine/WebView.py" \
    "$ZAPZAP_ROOT/webengine/WebView.py"

mkdir -p "$HOME/.config/press-to-talk"
CONFIG="$HOME/.config/press-to-talk/zapzap.json"
if [[ ! -f "$CONFIG" ]]; then
    cat >"$CONFIG" <<EOF
{
  "press_to_talk_dir": "$PTT_DIR"
}
EOF
    echo "Wrote $CONFIG"
fi

mkdir -p "$HOME/.cache/press-to-talk/transcripts"
mkdir -p "$HOME/.cache/press-to-talk/incoming"

echo ""
echo "Done. Restart ZapZap completely (quit from tray, reopen)."
echo ""
echo "Voice messages in open chats get transcribed automatically."
echo "No need to keep Press-to-Talk running — ZapZap calls the host GPU directly."
echo ""
echo "Cache: ~/.cache/press-to-talk/transcripts/"
echo "Logs:  ~/.cache/press-to-talk/zapzap.log"
echo ""
echo "Note: re-run this script after ZapZap Flatpak updates."
