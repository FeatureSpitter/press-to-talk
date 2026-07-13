#!/usr/bin/env bash
# Press-to-Talk installer for Linux Mint.
# Usage: ./install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_ID="press-to-talk.desktop"
DESKTOP_DEST="${HOME}/.local/share/applications/${DESKTOP_ID}"

info()  { printf '==> %s\n' "$*"; }
warn()  { printf 'warning: %s\n' "$*" >&2; }
die()   { printf 'error: %s\n' "$*" >&2; exit 1; }

require_linux_mint() {
    if [[ -f /etc/os-release ]]; then
        # shellcheck disable=SC1091
        source /etc/os-release
        if [[ "${ID:-}" == "linuxmint" ]]; then
            info "Linux Mint detected (${PRETTY_NAME:-unknown version})"
            return 0
        fi
    fi
    die "This installer only supports Linux Mint. Detected: ${PRETTY_NAME:-unknown OS}"
}

require_x11() {
    if [[ -z "${DISPLAY:-}" ]]; then
        die "DISPLAY is not set. Run this installer from an X11 desktop session."
    fi
    if [[ "${XDG_SESSION_TYPE:-}" == "wayland" ]]; then
        warn "Session type is Wayland. Press-to-Talk expects X11 for global hotkeys."
    fi
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

install_system_packages() {
    info "Installing system packages (sudo required)"
    require_command sudo
    sudo apt-get update
    sudo apt-get install -y \
        xclip \
        python3 \
        python3-gi \
        python3-gi-cairo \
        gir1.2-gtk-3.0 \
        gir1.2-ayatanaappindicator3-0.1 \
        libportaudio2 \
        curl \
        ca-certificates
}

ensure_uv() {
    if command -v uv >/dev/null 2>&1; then
        info "uv already installed ($(uv --version))"
        return 0
    fi
    info "Installing uv"
    curl -LsSf https://astral.sh/uv/install.sh | sh
    export PATH="${HOME}/.local/bin:${PATH}"
    command -v uv >/dev/null 2>&1 || die "uv install failed; add ~/.local/bin to PATH and retry"
}

setup_python_env() {
    info "Creating virtual environment and installing Python dependencies"
    cd "$ROOT"
    uv venv --python /usr/bin/python3 --system-site-packages
    uv sync
}

verify_gpu() {
    info "Checking NVIDIA GPU"
    if ! command -v nvidia-smi >/dev/null 2>&1; then
        die "nvidia-smi not found. Install NVIDIA drivers from Linux Mint Driver Manager, reboot, then rerun ./install.sh"
    fi
    if ! nvidia-smi >/dev/null 2>&1; then
        die "nvidia-smi failed. Fix NVIDIA drivers (Driver Manager), reboot, then rerun ./install.sh"
    fi
    nvidia-smi --query-gpu=name,driver_version --format=csv,noheader | while IFS= read -r line; do
        info "GPU: $line"
    done
}

prefetch_model() {
    info "Downloading Whisper model and verifying CUDA inference (this may take a while)"
    cd "$ROOT"
    uv run press_to_talk.py --check
}

install_menu_shortcut() {
    info "Installing Start Menu shortcut"
    mkdir -p "${HOME}/.local/share/applications"
    cat >"$DESKTOP_DEST" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Press to Talk
GenericName=Voice Dictation
Comment=Press Ctrl+M to start recording, release Ctrl to copy transcription
Exec=${ROOT}/launch.sh
Path=${ROOT}
Icon=audio-input-microphone
Terminal=false
Categories=Utility;Audio;
Keywords=dictation;voice;whisper;transcription;speech;
StartupNotify=true
X-GNOME-Autostart-enabled=false
EOF
    chmod +x "${ROOT}/launch.sh"
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "${HOME}/.local/share/applications" || true
    fi
    info "Shortcut installed: ${DESKTOP_DEST}"
}

print_done() {
    cat <<EOF

Press-to-Talk is installed.

  Launch from terminal:  ${ROOT}/launch.sh
  Launch from menu:      Start Menu -> Press to Talk

  Hotkeys:
    Ctrl+M        start recording
    Hold Ctrl     keep recording (M can be released)
    Release Ctrl  transcribe and copy to clipboard
    Ctrl+Q        quit (while Ctrl is held)

  Optional autostart:
    mkdir -p ~/.config/autostart
    cp ${DESKTOP_DEST} ~/.config/autostart/

EOF
}

main() {
    require_linux_mint
    require_x11
    install_system_packages
    ensure_uv
    setup_python_env
    verify_gpu
    prefetch_model
    install_menu_shortcut
    print_done
}

main "$@"
