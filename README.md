# Press to Talk

Local **press-to-talk** tool for Linux Mint: press **Ctrl+M** to start recording, keep **Ctrl** held while you speak (you can release M), then release **Ctrl** to transcribe with [faster-whisper](https://github.com/SYSTRAN/faster-whisper) and copy the result to the clipboard.

Runs in the background with a system tray icon and a small status popup in the bottom-right corner.

**Supported platform: Linux Mint only** (X11/Cinnamon).

## Features

- **Hotkey recording**: Ctrl+M to start; hold Ctrl while speaking (M can be released)
- **Local transcription**: Whisper model (default `large-v3-turbo`) on NVIDIA CUDA
- **Clipboard**: transcribed text copied automatically via `xclip`
- **Language detection**: Portuguese and English (auto-detect)
- **X11 key suppression**: Ctrl+M is blocked from other apps while the service runs
- **Single instance**: launching again replaces the previous instance

## Quick install (Linux Mint)

```bash
git clone git@github.com:FeatureSpitter/press-to-talk.git
cd press-to-talk
chmod +x install.sh
./install.sh
```

The installer:

1. Verifies you are on **Linux Mint** (exits with an error otherwise)
2. Installs system packages (`xclip`, GTK, tray icon libs, PortAudio, etc.)
3. Installs [uv](https://docs.astral.sh/uv/) if missing
4. Creates the Python venv and syncs dependencies
5. Verifies **NVIDIA GPU** drivers (`nvidia-smi`)
6. Downloads the Whisper model and runs a CUDA smoke test
7. Adds **Press to Talk** to the Start Menu

If NVIDIA drivers are missing, install them from **Driver Manager**, reboot, and rerun `./install.sh`.

## Manual install

```bash
uv venv --python /usr/bin/python3 --system-site-packages
uv sync
uv run press_to_talk.py --check
```

The venv needs `--system-site-packages` so GTK bindings from the system are available.

## Usage

```bash
./launch.sh
```

Or from the **Start Menu** → **Press to Talk**.

1. Wait for the model to load (“Loading model...”).
2. Press **Ctrl+M** to start recording.
3. Keep **Ctrl** held while speaking (you can release M).
4. Release **Ctrl** → transcribes and copies to the clipboard.
5. Paste with Ctrl+V wherever you need.

Other shortcuts:

- **Ctrl+Q** (while Ctrl is held): quit
- **Right-click tray icon** → **Settings** or **Quit**

### Settings

Open **Settings** from the tray menu to:

- Choose the **recording device** (defaults to the system input)
- **Test the microphone** with a live level meter before saving
- Enable **auto-paste** to send Ctrl+V to the focused window after transcription

Settings are saved to `~/.config/press-to-talk/settings.json`.

If no speech is detected (muted mic, low levels, etc.), the popup shows **“No speech detected”**.

### CLI options

```bash
uv run press_to_talk.py --model large-v3-turbo --device cuda --compute-type float16
uv run press_to_talk.py --language pt          # force Portuguese
uv run press_to_talk.py --device cpu           # no GPU
```

Disable X11 key suppression:

```bash
PTT_NO_GRAB=1 uv run press_to_talk.py
```

## Start Menu, favorites, and autostart

`./install.sh` creates `~/.local/share/applications/press-to-talk.desktop` automatically.

To pin to favorites in Cinnamon: Start Menu → search **Press to Talk** → right-click → **Add to favorites**.

Optional autostart on login:

```bash
mkdir -p ~/.config/autostart
cp ~/.local/share/applications/press-to-talk.desktop ~/.config/autostart/
```

## Microphone

Check **System Settings → Sound → Input** (or `pavucontrol`) that the correct mic is selected and not muted. If the app records silence, you will see **“No speech detected”** instead of clipboard text.

## Tests

```bash
uv run pytest test_press_to_talk.py -v
```

## License

Personal / local utility project. Use at your own risk.
