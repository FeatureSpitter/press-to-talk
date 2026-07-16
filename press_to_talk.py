#!/usr/bin/env python3
"""
Press-to-Talk Local Transcription Tool

Hold Ctrl+M to start recording, keep Ctrl held while speaking, release Ctrl to
transcribe locally with faster-whisper and copy the result to the clipboard. A small Gtk overlay shows status without
stealing focus.

Setup:
  cd ~/projectos/press-to-talk
  uv venv --python /usr/bin/python3 --system-site-packages
  uv sync
  uv run press_to_talk.py

Requires: xclip, python3-gi (GTK3), NVIDIA GPU with CUDA for best performance.
The venv must include system site packages so Gtk bindings are available.
"""

from __future__ import annotations

import argparse
import atexit
import fcntl
import json
import logging
import os
import select
import shutil
import signal
import socket
import subprocess
import sys
import threading
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
from typing import Callable, Optional

import numpy as np

SAMPLE_RATE = 16_000
CHANNELS = 1
DEFAULT_MODEL = "large-v3-turbo"
DEFAULT_DEVICE = "cuda"
DEFAULT_COMPUTE_TYPE = "float16"
DEFAULT_BEAM_SIZE = 5
VALID_LANGUAGES = {"en", "pt"}
MIN_RECORDING_SECONDS = 0.05
DONE_HIDE_MS = 1500
PULSE_INTERVAL_MS = 500
MIC_TEST_POLL_MS = 50
MIC_LEVEL_DECAY = 0.85
SETTINGS_DIR = Path(
    os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")
) / "press-to-talk"
SETTINGS_PATH = SETTINGS_DIR / "settings.json"

logger = logging.getLogger(__name__)


class OverlayState(str, Enum):
    IDLE = "idle"
    LOADING = "loading"
    RECORDING = "recording"
    TRANSCRIBING = "transcribing"
    DONE = "done"
    ERROR = "error"


OVERLAY_MESSAGES = {
    OverlayState.LOADING: "Loading model...",
    OverlayState.RECORDING: "Recording...",
    OverlayState.TRANSCRIBING: "Transcribing...",
    OverlayState.DONE: "Copied!",
    OverlayState.ERROR: "Error",
}

OVERLAY_COLORS = {
    OverlayState.LOADING: (0.35, 0.45, 0.55, 0.92),
    OverlayState.RECORDING: (0.91, 0.30, 0.24, 0.92),
    OverlayState.TRANSCRIBING: (0.95, 0.61, 0.07, 0.92),
    OverlayState.DONE: (0.15, 0.68, 0.38, 0.92),
    OverlayState.ERROR: (0.75, 0.15, 0.15, 0.92),
}


@dataclass
class UserSettings:
    input_device: Optional[int] = None
    auto_paste: bool = False


class SettingsStore:
    """Persist user preferences to disk."""

    def __init__(self, path: Path = SETTINGS_PATH) -> None:
        self.path = path

    def load(self) -> UserSettings:
        if not self.path.is_file():
            return UserSettings()
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            device = data.get("input_device")
            return UserSettings(
                input_device=int(device) if device is not None else None,
                auto_paste=bool(data.get("auto_paste", False)),
            )
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            logger.warning("Failed to load settings from %s: %s", self.path, exc)
            return UserSettings()

    def save(self, settings: UserSettings) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = asdict(settings)
        self.path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def list_input_devices(sounddevice_module=None) -> list[tuple[Optional[int], str]]:
    sd = sounddevice_module or __import__("sounddevice")
    devices: list[tuple[Optional[int], str]] = [(None, "System default")]
    for index, info in enumerate(sd.query_devices()):
        if info["max_input_channels"] > 0:
            devices.append((index, info["name"]))
    return devices


@dataclass(frozen=True)
class AppConfig:
    model: str = DEFAULT_MODEL
    device: str = DEFAULT_DEVICE
    compute_type: str = DEFAULT_COMPUTE_TYPE
    language: Optional[str] = None
    beam_size: int = DEFAULT_BEAM_SIZE
    xclip_path: str = "xclip"

    def validate(self) -> None:
        if self.language is not None and self.language not in VALID_LANGUAGES:
            raise ValueError(
                f"Unsupported language '{self.language}'. "
                f"Use one of: {', '.join(sorted(VALID_LANGUAGES))}, or omit for auto-detect."
            )
        if self.beam_size < 1:
            raise ValueError("beam_size must be at least 1")


def parse_args(argv: Optional[list[str]] = None) -> AppConfig:
    parser = argparse.ArgumentParser(
        description="Press Ctrl+M to record, keep Ctrl held, release Ctrl to transcribe."
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"Whisper model name (default: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--device",
        default=DEFAULT_DEVICE,
        help=f"Inference device (default: {DEFAULT_DEVICE})",
    )
    parser.add_argument(
        "--compute-type",
        default=DEFAULT_COMPUTE_TYPE,
        help=f"Compute precision (default: {DEFAULT_COMPUTE_TYPE})",
    )
    parser.add_argument(
        "--language",
        default=None,
        help="Optional. Normally omitted: Whisper auto-detects pt/en from your speech.",
    )
    parser.add_argument(
        "--beam-size",
        type=int,
        default=DEFAULT_BEAM_SIZE,
        help=f"Beam search size (default: {DEFAULT_BEAM_SIZE})",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Load the model and exit (smoke test).",
    )
    parser.add_argument(
        "--transcribe-file",
        metavar="PATH",
        help="Transcribe an audio file and print the result to stdout (CLI mode).",
    )
    parser.add_argument(
        "--serve",
        action="store_true",
        help="Start headless socket server for ZapZap integration (load model once, serve many).",
    )
    args = parser.parse_args(argv)
    config = AppConfig(
        model=args.model,
        device=args.device,
        compute_type=args.compute_type,
        language=args.language,
        beam_size=args.beam_size,
        xclip_path=shutil.which("xclip") or "xclip",
    )
    config.validate()
    return config, args.check, args.transcribe_file, args.serve


class AudioRecorder:
    """Capture mono 16 kHz float32 audio while recording is active."""

    def __init__(
        self,
        sample_rate: int = SAMPLE_RATE,
        channels: int = CHANNELS,
        input_device: Optional[int] = None,
        sounddevice_module=None,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self.input_device = input_device
        self._sd = sounddevice_module or __import__("sounddevice")
        self._stream = None
        self._chunks: list[np.ndarray] = []
        self._lock = threading.Lock()

    def set_input_device(self, device: Optional[int]) -> None:
        if self.is_recording:
            raise RuntimeError("Cannot change input device while recording")
        self.input_device = device

    def _audio_callback(self, indata, frames, time_info, status) -> None:
        if status:
            logger.warning("Audio status: %s", status)
        with self._lock:
            self._chunks.append(indata.copy())

    def start(self) -> None:
        if self._stream is not None:
            return
        self._chunks = []
        stream_kwargs = {
            "samplerate": self.sample_rate,
            "channels": self.channels,
            "dtype": "float32",
            "callback": self._audio_callback,
        }
        if self.input_device is not None:
            stream_kwargs["device"] = self.input_device
        self._stream = self._sd.InputStream(**stream_kwargs)
        self._stream.start()

    def stop(self) -> np.ndarray:
        if self._stream is None:
            return np.array([], dtype=np.float32)
        self._stream.stop()
        self._stream.close()
        self._stream = None
        with self._lock:
            if not self._chunks:
                return np.array([], dtype=np.float32)
            audio = np.concatenate(self._chunks, axis=0)
            self._chunks = []
        return audio.reshape(-1).astype(np.float32, copy=False)

    @property
    def is_recording(self) -> bool:
        return self._stream is not None


class MicLevelMonitor:
    """Live microphone level monitor for the settings dialog."""

    def __init__(
        self,
        sample_rate: int = SAMPLE_RATE,
        channels: int = CHANNELS,
        sounddevice_module=None,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self._sd = sounddevice_module or __import__("sounddevice")
        self._stream = None
        self._lock = threading.Lock()
        self._level = 0.0
        self.error: Optional[str] = None

    @property
    def is_active(self) -> bool:
        return self._stream is not None

    def _audio_callback(self, indata, frames, time_info, status) -> None:
        if status:
            logger.warning("Mic test audio status: %s", status)
        peak = float(np.max(np.abs(indata)))
        with self._lock:
            self._level = max(self._level * MIC_LEVEL_DECAY, peak)

    def read_level(self) -> float:
        with self._lock:
            return self._level

    def start(self, device: Optional[int] = None) -> None:
        self.stop()
        self.error = None
        self._level = 0.0
        stream_kwargs = {
            "samplerate": self.sample_rate,
            "channels": self.channels,
            "dtype": "float32",
            "callback": self._audio_callback,
        }
        if device is not None:
            stream_kwargs["device"] = device
        try:
            self._stream = self._sd.InputStream(**stream_kwargs)
            self._stream.start()
        except Exception as exc:
            self.error = str(exc)
            self._stream = None

    def stop(self) -> None:
        if self._stream is None:
            return
        try:
            self._stream.stop()
            self._stream.close()
        except Exception:
            pass
        self._stream = None
        with self._lock:
            self._level = 0.0


class Transcriber:
    """Wrap faster-whisper with accuracy-focused defaults."""

    def __init__(self, config: AppConfig, whisper_model=None) -> None:
        self.config = config
        self._model = whisper_model

    def load(self) -> None:
        if self._model is not None:
            return
        from faster_whisper import WhisperModel

        self._model = WhisperModel(
            self.config.model,
            device=self.config.device,
            compute_type=self.config.compute_type,
        )

    @property
    def is_loaded(self) -> bool:
        return self._model is not None

    def _transcribe_kwargs(self) -> dict:
        kwargs = {
            "beam_size": self.config.beam_size,
            "vad_filter": True,
            "condition_on_previous_text": False,
        }
        if self.config.language:
            kwargs["language"] = self.config.language
        return kwargs

    @staticmethod
    def _join_segments(segments, pause_threshold: float = 1.5) -> str:
        """Join segments, inserting paragraph breaks at natural pauses."""
        parts: list[str] = []
        prev_end = 0.0
        for seg in segments:
            text = seg.text
            if not text:
                continue
            gap = seg.start - prev_end if prev_end > 0 else 0.0
            if gap >= pause_threshold and parts:
                parts.append("\n\n")
                parts.append(text.strip())
            else:
                parts.append(text)
            prev_end = seg.end
        return "".join(parts).strip()

    def transcribe(self, audio: np.ndarray) -> str:
        if self._model is None:
            raise RuntimeError("Model is not loaded")
        if audio.size == 0:
            return ""
        segments, _info = self._model.transcribe(audio, **self._transcribe_kwargs())
        return self._join_segments(segments)

    def transcribe_file(self, path: str) -> str:
        if self._model is None:
            raise RuntimeError("Model is not loaded")
        segments, _info = self._model.transcribe(path, **self._transcribe_kwargs())
        return self._join_segments(segments)


class ClipboardManager:
    """Copy text to the X11 clipboard via xclip."""

    def __init__(self, xclip_path: str = "xclip", subprocess_module=None) -> None:
        self.xclip_path = xclip_path
        self._subprocess = subprocess_module or subprocess

    def copy(self, text: str) -> None:
        if not text:
            return
        if not shutil.which(self.xclip_path):
            raise FileNotFoundError(
                f"xclip not found at '{self.xclip_path}'. Install with: sudo apt install xclip"
            )
        self._subprocess.run(
            [self.xclip_path, "-selection", "clipboard"],
            input=text.encode("utf-8"),
            check=True,
        )

    def paste(self) -> None:
        from pynput.keyboard import Controller, Key

        controller = Controller()
        with controller.pressed(Key.ctrl):
            controller.press("v")
            controller.release("v")


class HotkeyDetector:
    """Detect hold-to-talk triggered by Ctrl+M (left or right Ctrl).

    Ctrl+M starts recording. While recording, only Ctrl must stay held — M can
    be released. Releasing Ctrl stops recording and triggers transcription.

    On X11, grabs Ctrl+M at the display server so other apps never receive it.
    Key releases are detected via both pynput (XRecord) and the X11 grab event
    stream for redundancy.
    """

    CTRL_KEYS = frozenset()

    def __init__(
        self,
        on_press: Callable[[], None],
        on_release: Callable[[], None],
        on_quit: Optional[Callable[[], None]] = None,
        keyboard_listener_cls=None,
        key_cls=None,
        use_x11_grab: Optional[bool] = None,
    ) -> None:
        from pynput import keyboard

        self._keyboard = keyboard
        self._Key = key_cls or keyboard.Key
        self._listener_cls = keyboard_listener_cls or keyboard.Listener
        self._on_press = on_press
        self._on_release = on_release
        self._on_quit = on_quit
        self._ctrl_held = False
        self._m_held = False
        self._recording = False
        self._listener = None
        self._x11_thread = None
        self._x11_running = False
        self._x11_display = None
        self._x11_grab_win = None
        self._x11_grabbed_keys: list[tuple[int, int]] = []
        self._use_x11_grab = (
            use_x11_grab
            if use_x11_grab is not None
            else os.environ.get("PTT_NO_GRAB") != "1"
        )
        self._lock = threading.Lock()

        if not HotkeyDetector.CTRL_KEYS:
            HotkeyDetector.CTRL_KEYS = frozenset(
                {self._Key.ctrl, self._Key.ctrl_l, self._Key.ctrl_r}
            )

    def _is_ctrl(self, key) -> bool:
        return key in self.CTRL_KEYS

    def _is_m(self, key) -> bool:
        if hasattr(key, "char") and key.char:
            return key.char.lower() == "m"
        return False

    def _is_q(self, key) -> bool:
        if hasattr(key, "char") and key.char:
            return key.char.lower() == "q"
        return False

    def _try_start_recording(self) -> None:
        if self._ctrl_held and self._m_held and not self._recording:
            self._recording = True
            self._on_press()

    def _try_stop_recording(self) -> None:
        if self._recording and not self._ctrl_held:
            self._recording = False
            self._on_release()

    def handle_press(self, key) -> None:
        with self._lock:
            if self._is_ctrl(key):
                self._ctrl_held = True
                self._try_start_recording()
            elif self._is_m(key):
                self._m_held = True
                self._try_start_recording()
            elif self._ctrl_held and self._is_q(key) and self._on_quit:
                self._on_quit()
                return

    def handle_release(self, key) -> None:
        with self._lock:
            if self._is_ctrl(key):
                self._ctrl_held = False
                self._try_stop_recording()
            elif self._is_m(key):
                self._m_held = False

    @staticmethod
    def _control_modifier_masks() -> list[int]:
        import Xlib.X

        ctrl = Xlib.X.ControlMask
        lock = Xlib.X.LockMask
        mod2 = Xlib.X.Mod2Mask
        return [ctrl, ctrl | lock, ctrl | mod2, ctrl | lock | mod2]

    def _x11_grab_combo_keys(
        self, display, grab_win
    ) -> tuple[set[int], int, int]:
        import Xlib.X
        import Xlib.XK

        ctrl_codes = {
            display.keysym_to_keycode(Xlib.XK.XK_Control_L),
            display.keysym_to_keycode(Xlib.XK.XK_Control_R),
        }
        m_code = display.keysym_to_keycode(Xlib.XK.XK_m)
        q_code = display.keysym_to_keycode(Xlib.XK.XK_q)

        for mod in self._no_modifier_masks():
            for code in ctrl_codes:
                self._x11_grab_key(grab_win, code, mod, "ctrl")

        for mod in self._control_modifier_masks():
            for code, name in ((m_code, "m"), (q_code, "q")):
                self._x11_grab_key(grab_win, code, mod, name)

        return ctrl_codes, m_code, q_code

    @staticmethod
    def _no_modifier_masks() -> list[int]:
        import Xlib.X

        lock = Xlib.X.LockMask
        mod2 = Xlib.X.Mod2Mask
        return [0, lock, mod2, lock | mod2]

    def _x11_grab_key(self, grab_win, keycode: int, modifiers: int, name: str) -> None:
        import Xlib.X

        try:
            grab_win.grab_key(
                keycode,
                modifiers,
                False,
                Xlib.X.GrabModeAsync,
                Xlib.X.GrabModeAsync,
            )
            self._x11_grabbed_keys.append((keycode, modifiers))
        except Exception as exc:
            logger.warning(
                "Failed to grab key %s (code=%s, mod=%s): %s",
                name,
                keycode,
                modifiers,
                exc,
            )

    def _x11_keycode_to_pynput(self, event, ctrl_codes, m_code, q_code):
        """Translate an X11 keycode to the equivalent pynput key object."""
        keycode = event.detail
        if keycode in ctrl_codes:
            return self._Key.ctrl_l
        if keycode == m_code:
            try:
                return self._keyboard.KeyCode.from_char("m")
            except Exception:
                return None
        if keycode == q_code:
            try:
                return self._keyboard.KeyCode.from_char("q")
            except Exception:
                return None
        return None

    def _run_x11_grabber(self) -> None:
        import Xlib.X
        import Xlib.display

        display = Xlib.display.Display()
        self._x11_display = display
        root = display.screen().root
        grab_win = root.create_window(
            -100,
            -100,
            1,
            1,
            0,
            Xlib.X.CopyFromParent,
            Xlib.X.InputOutput,
            Xlib.X.CopyFromParent,
            event_mask=Xlib.X.KeyPressMask | Xlib.X.KeyReleaseMask,
            override_redirect=True,
        )
        grab_win.map()
        self._x11_grab_win = grab_win

        ctrl_codes, m_code, q_code = self._x11_grab_combo_keys(display, grab_win)
        display.sync()

        logger.info(
            "X11 key suppression active: Ctrl+M/Q blocked for other apps"
        )

        while self._x11_running:
            ready, _, _ = select.select([display.fileno()], [], [], 0.2)
            if not self._x11_running:
                break
            if not ready:
                continue
            while display.pending_events():
                event = display.next_event()
                if event.type not in (Xlib.X.KeyPress, Xlib.X.KeyRelease):
                    continue
                key = self._x11_keycode_to_pynput(
                    event, ctrl_codes, m_code, q_code
                )
                if key is None:
                    continue
                if event.type == Xlib.X.KeyRelease:
                    self.handle_release(key)
                elif event.type == Xlib.X.KeyPress:
                    self.handle_press(key)

        if grab_win is not None:
            for keycode, modifiers in self._x11_grabbed_keys:
                try:
                    grab_win.ungrab_key(keycode, modifiers, Xlib.X.CurrentTime)
                except Exception:
                    pass
            self._x11_grabbed_keys = []
            try:
                grab_win.destroy()
            except Exception:
                pass
        try:
            display.flush()
            display.close()
        except Exception:
            pass
        self._x11_display = None
        self._x11_grab_win = None

    def _start_pynput_listener(self) -> None:
        self._listener = self._listener_cls(
            on_press=self.handle_press,
            on_release=self.handle_release,
        )
        self._listener.start()
        logger.info("Hotkey listener active (Ctrl+M to start, release Ctrl to stop)")

    def start(self) -> None:
        if self._listener is not None or self._x11_thread is not None:
            return

        self._start_pynput_listener()

        if self._use_x11_grab and sys.platform.startswith("linux"):
            try:
                self._x11_running = True
                self._x11_thread = threading.Thread(
                    target=self._run_x11_grabber,
                    name="x11-hotkey-grabber",
                    daemon=True,
                )
                self._x11_thread.start()
            except Exception as exc:
                logger.warning("X11 key suppression failed: %s", exc)
                self._x11_running = False
                self._x11_thread = None

    def stop(self) -> None:
        if self._x11_thread is not None:
            self._x11_running = False
            self._x11_thread.join(timeout=1.0)
            self._x11_thread = None

        if self._listener is not None:
            self._listener.stop()
            self._listener = None


class StatusOverlay:
    """Small always-on-top Gtk pill that never steals focus."""

    WIDTH = 180
    HEIGHT = 40
    MARGIN = 16

    def __init__(self, gtk_modules=None) -> None:
        if gtk_modules is None:
            import gi

            gi.require_version("Gtk", "3.0")
            gi.require_version("Gdk", "3.0")
            from gi.repository import Gdk, GLib, Gtk

            self._Gtk = Gtk
            self._Gdk = Gdk
            self._GLib = GLib
        else:
            self._Gtk, self._Gdk, self._GLib = gtk_modules

        self._state = OverlayState.IDLE
        self._window = None
        self._label = None
        self._box = None
        self._pulse_source_id = None
        self._hide_source_id = None
        self._pulse_on = False
        self._built = False

    @property
    def state(self) -> OverlayState:
        return self._state

    def build(self) -> None:
        if self._built:
            return

        Gtk = self._Gtk
        Gdk = self._Gdk

        self._window = Gtk.Window(type=Gtk.WindowType.TOPLEVEL)
        self._window.set_title("Press-to-Talk")
        self._window.set_decorated(False)
        self._window.set_resizable(False)
        self._window.set_keep_above(True)
        self._window.set_skip_taskbar_hint(True)
        self._window.set_skip_pager_hint(True)
        self._window.set_accept_focus(False)
        self._window.set_focus_on_map(False)
        self._window.set_type_hint(Gdk.WindowTypeHint.NOTIFICATION)
        self._window.set_default_size(self.WIDTH, self.HEIGHT)

        screen = Gdk.Screen.get_default()
        if screen is not None:
            visual = screen.get_rgba_visual()
            if visual is not None and screen.is_composited():
                self._window.set_visual(visual)

        self._box = Gtk.EventBox()
        self._label = Gtk.Label()
        self._label.set_margin_start(12)
        self._label.set_margin_end(12)
        self._label.set_margin_top(8)
        self._label.set_margin_bottom(8)
        self._box.add(self._label)
        self._window.add(self._box)
        self._window.connect("delete-event", lambda *_: True)
        self._position_bottom_right()
        self._built = True

    def _position_bottom_right(self) -> None:
        Gdk = self._Gdk
        display = Gdk.Display.get_default()
        if display is None:
            return
        monitor = display.get_primary_monitor()
        if monitor is None:
            return
        workarea = monitor.get_workarea()
        x = workarea.x + workarea.width - self.WIDTH - self.MARGIN
        y = workarea.y + workarea.height - self.HEIGHT - self.MARGIN
        self._window.move(x, y)

    def _apply_color(self, rgba: tuple[float, float, float, float]) -> None:
        r, g, b, a = rgba
        css = (
            f".ptt-overlay {{ background-color: rgba({int(r * 255)}, "
            f"{int(g * 255)}, {int(b * 255)}, {a}); border-radius: 18px; }}"
        )
        provider = self._Gtk.CssProvider()
        provider.load_from_data(css.encode("utf-8"))
        context = self._box.get_style_context()
        context.add_class("ptt-overlay")
        context.add_provider(
            provider,
            self._Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

    def _cancel_timers(self) -> None:
        if self._pulse_source_id is not None:
            self._GLib.source_remove(self._pulse_source_id)
            self._pulse_source_id = None
        if self._hide_source_id is not None:
            self._GLib.source_remove(self._hide_source_id)
            self._hide_source_id = None

    def _start_pulse(self) -> None:
        self._cancel_pulse()

        def pulse() -> bool:
            self._pulse_on = not self._pulse_on
            alpha = 0.92 if self._pulse_on else 0.72
            color = OVERLAY_COLORS[OverlayState.RECORDING]
            self._apply_color((color[0], color[1], color[2], alpha))
            return True

        self._pulse_source_id = self._GLib.timeout_add(PULSE_INTERVAL_MS, pulse)

    def _cancel_pulse(self) -> None:
        if self._pulse_source_id is not None:
            self._GLib.source_remove(self._pulse_source_id)
            self._pulse_source_id = None

    def set_state(self, state: OverlayState, message: Optional[str] = None) -> None:
        self._state = state
        if not self._built:
            self.build()

        if state == OverlayState.IDLE:
            self._cancel_timers()
            self._window.hide()
            return

        text = message or OVERLAY_MESSAGES.get(state, state.value)
        self._label.set_text(text)
        self._apply_color(OVERLAY_COLORS[state])
        self._window.show_all()
        self._position_bottom_right()

        self._cancel_pulse()
        if state == OverlayState.RECORDING:
            self._start_pulse()
        elif state == OverlayState.DONE:
            self._schedule_hide()
        else:
            self._cancel_timers()

    def set_error(self, message: str) -> None:
        self.set_state(OverlayState.ERROR, message)

    def _schedule_hide(self) -> None:
        if self._hide_source_id is not None:
            self._GLib.source_remove(self._hide_source_id)

        def hide() -> bool:
            self.set_state(OverlayState.IDLE)
            return False

        self._hide_source_id = self._GLib.timeout_add(DONE_HIDE_MS, hide)


class SettingsDialog:
    """Gtk dialog for input device selection, mic test, and auto-paste."""

    def __init__(
        self,
        settings: UserSettings,
        on_save: Callable[[UserSettings], None],
        sounddevice_module=None,
        parent=None,
        gtk_modules=None,
        glib_module=None,
    ) -> None:
        if gtk_modules is None:
            import gi

            gi.require_version("Gtk", "3.0")
            from gi.repository import GLib, Gtk

            self._Gtk = Gtk
            self._GLib = glib_module or GLib
        else:
            self._Gtk = gtk_modules
            self._GLib = glib_module

        self._settings = settings
        self._on_save = on_save
        self._sd = sounddevice_module
        self._parent = parent
        self._dialog = None
        self._device_combo = None
        self._auto_paste_check = None
        self._test_button = None
        self._level_bar = None
        self._level_label = None
        self._monitor = MicLevelMonitor(sounddevice_module=sounddevice_module)
        self._poll_source_id = None
        self._devices: list[tuple[Optional[int], str]] = []

    def _selected_device(self) -> Optional[int]:
        combo_index = self._device_combo.get_active()
        if combo_index < 0:
            return self._settings.input_device
        return self._devices[combo_index][0]

    def _level_hint(self, level: float) -> str:
        if level < 0.01:
            return "Silent — check device or speak louder"
        if level < 0.05:
            return "Low level"
        return "Good level"

    def _poll_level(self) -> bool:
        if not self._monitor.is_active:
            return False
        level = min(1.0, self._monitor.read_level())
        self._level_bar.set_fraction(level)
        pct = int(level * 100)
        self._level_label.set_text(
            f"Peak: {pct}% — {self._level_hint(level)}"
        )
        return True

    def _stop_test(self) -> None:
        if self._poll_source_id is not None:
            self._GLib.source_remove(self._poll_source_id)
            self._poll_source_id = None
        self._monitor.stop()
        if self._test_button is not None:
            self._test_button.set_label("Test microphone")
        if self._level_bar is not None:
            self._level_bar.set_fraction(0.0)
        if self._level_label is not None:
            self._level_label.set_text("Click Test microphone and speak.")

    def _toggle_test(self, _button) -> None:
        if self._monitor.is_active:
            self._stop_test()
            return

        self._monitor.start(self._selected_device())
        if self._monitor.error:
            self._level_label.set_text(f"Mic error: {self._monitor.error}")
            return

        self._test_button.set_label("Stop test")
        self._level_label.set_text("Listening… speak into the microphone.")
        self._poll_source_id = self._GLib.timeout_add(
            MIC_TEST_POLL_MS, self._poll_level
        )

    def show(self) -> None:
        Gtk = self._Gtk
        self._devices = list_input_devices(self._sd)

        self._dialog = Gtk.Dialog(
            title="Press to Talk Settings",
            transient_for=self._parent,
            modal=True,
        )
        self._dialog.set_default_size(420, -1)
        self._dialog.add_button("Cancel", Gtk.ResponseType.CANCEL)
        self._dialog.add_button("Save", Gtk.ResponseType.OK)

        content = self._dialog.get_content_area()
        content.set_border_width(12)
        content.set_spacing(10)

        device_label = Gtk.Label(label="Recording device:", xalign=0)
        content.pack_start(device_label, False, False, 0)

        self._device_combo = Gtk.ComboBoxText()
        selected_index = 0
        for index, (device_id, name) in enumerate(self._devices):
            self._device_combo.append(str(index), name)
            if device_id == self._settings.input_device:
                selected_index = index
        self._device_combo.set_active(selected_index)
        self._device_combo.connect("changed", lambda *_: self._stop_test())
        content.pack_start(self._device_combo, False, False, 0)

        test_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self._test_button = Gtk.Button(label="Test microphone")
        self._test_button.connect("clicked", self._toggle_test)
        test_box.pack_start(self._test_button, False, False, 0)

        self._level_bar = Gtk.ProgressBar()
        self._level_bar.set_show_text(False)
        test_box.pack_start(self._level_bar, False, False, 0)

        self._level_label = Gtk.Label(
            label="Click Test microphone and speak.",
            xalign=0,
        )
        test_box.pack_start(self._level_label, False, False, 0)
        content.pack_start(test_box, False, False, 0)

        self._auto_paste_check = Gtk.CheckButton(
            label="Auto-paste after recording (Ctrl+V)"
        )
        self._auto_paste_check.set_active(self._settings.auto_paste)
        self._auto_paste_check.set_tooltip_text(
            "After transcription, paste into the currently focused window."
        )
        content.pack_start(self._auto_paste_check, False, False, 0)

        self._dialog.show_all()
        try:
            response = self._dialog.run()
            if response == Gtk.ResponseType.OK:
                combo_index = self._device_combo.get_active()
                device_id = self._devices[combo_index][0]
                updated = UserSettings(
                    input_device=device_id,
                    auto_paste=self._auto_paste_check.get_active(),
                )
                self._on_save(updated)
        finally:
            self._stop_test()
            self._dialog.destroy()
            self._dialog = None


class TrayIcon:
    """System tray icon with Transcribe file, Settings, and Quit menu items."""

    ICON_NAME = "audio-input-microphone"
    TOOLTIP = "Press to Talk\nCtrl+M to start, release Ctrl to copy"

    def __init__(
        self,
        on_quit: Callable[[], None],
        on_settings: Optional[Callable[[], None]] = None,
        on_file_transcribe: Optional[Callable[[], None]] = None,
        gtk_modules=None,
        app_indicator_module=None,
    ) -> None:
        if gtk_modules is None:
            import gi

            gi.require_version("Gtk", "3.0")
            from gi.repository import Gtk

            self._Gtk = Gtk
            self._AppIndicator = None
            if app_indicator_module is None:
                try:
                    gi.require_version("AyatanaAppIndicator3", "0.1")
                    from gi.repository import AyatanaAppIndicator3

                    self._AppIndicator = AyatanaAppIndicator3
                except (ImportError, ValueError):
                    self._AppIndicator = None
            else:
                self._AppIndicator = app_indicator_module
        else:
            self._Gtk, self._AppIndicator = gtk_modules

        self._on_quit = on_quit
        self._on_settings = on_settings
        self._on_file_transcribe = on_file_transcribe
        self._indicator = None
        self._status_icon = None
        self._menu = None
        self._built = False

    def _build_menu(self):
        Gtk = self._Gtk
        menu = Gtk.Menu()

        if self._on_file_transcribe is not None:
            transcribe_item = Gtk.MenuItem(label="Transcribe file...")
            transcribe_item.connect(
                "activate", lambda *_: self._on_file_transcribe()
            )
            transcribe_item.show()
            menu.append(transcribe_item)

        if self._on_settings is not None:
            settings_item = Gtk.MenuItem(label="Settings")
            settings_item.connect("activate", lambda *_: self._on_settings())
            settings_item.show()
            menu.append(settings_item)

        quit_item = Gtk.MenuItem(label="Quit")
        quit_item.connect("activate", lambda *_: self._on_quit())
        quit_item.show()
        menu.append(quit_item)

        menu.show_all()
        self._menu = menu
        return menu

    def build(self) -> None:
        if self._built:
            return

        Gtk = self._Gtk
        menu = self._build_menu()

        if self._AppIndicator is not None:
            indicator = self._AppIndicator.Indicator.new(
                "press-to-talk",
                "Press to Talk",
                self._AppIndicator.IndicatorCategory.APPLICATION_STATUS,
            )
            indicator.set_status(self._AppIndicator.IndicatorStatus.ACTIVE)
            indicator.set_icon_full(self.ICON_NAME, self.TOOLTIP)
            indicator.set_title("Press to Talk")
            indicator.set_menu(menu)
            self._indicator = indicator
        else:
            status_icon = Gtk.StatusIcon()
            status_icon.set_from_icon_name(self.ICON_NAME)
            status_icon.set_tooltip_text(self.TOOLTIP)
            status_icon.connect("activate", self._show_menu)
            status_icon.connect("popup-menu", self._show_menu)
            status_icon.set_visible(True)
            self._status_icon = status_icon

        self._built = True
        logger.info("Tray icon ready (right-click for menu)")

    def _show_menu(self, icon, button=None, time=None) -> None:
        Gtk = self._Gtk
        if self._menu is None:
            return
        if time is None:
            time = Gtk.get_current_event_time()
        self._menu.popup(None, None, Gtk.StatusIcon.position_menu, icon, button, time)

    def destroy(self) -> None:
        if self._indicator is not None:
            self._indicator.set_status(
                self._AppIndicator.IndicatorStatus.PASSIVE
            )
            self._indicator = None
        if self._status_icon is not None:
            self._status_icon.set_visible(False)
            self._status_icon = None
        self._menu = None
        self._built = False


class PressToTalkApp:
    """Orchestrate recording, transcription, clipboard, and overlay."""

    def __init__(
        self,
        config: AppConfig,
        overlay: Optional[StatusOverlay] = None,
        recorder: Optional[AudioRecorder] = None,
        transcriber: Optional[Transcriber] = None,
        clipboard: Optional[ClipboardManager] = None,
        hotkeys: Optional[HotkeyDetector] = None,
        tray: Optional[TrayIcon] = None,
        settings_store: Optional[SettingsStore] = None,
        glib_module=None,
    ) -> None:
        self.config = config
        self.settings_store = settings_store or SettingsStore()
        self.settings = self.settings_store.load()
        self.overlay = overlay or StatusOverlay()
        self.recorder = recorder or AudioRecorder(
            input_device=self.settings.input_device
        )
        self.transcriber = transcriber or Transcriber(config)
        self.clipboard = clipboard or ClipboardManager(config.xclip_path)
        self.hotkeys = hotkeys
        self.tray = tray
        self._glib = glib_module
        self._busy = False
        self._model_ready = False
        self._should_quit = False
        self._file_dialog_open = False
        self._gtk = None
        self._socket_server: Optional[TranscriptionSocketServer] = None

    def _idle_add(self, callback: Callable[[], None]) -> None:
        if self._glib is not None:
            self._glib.idle_add(callback)
        else:
            from gi.repository import GLib

            GLib.idle_add(callback)

    def on_hotkey_press(self) -> None:
        if not self._model_ready:
            self._idle_add(
                lambda: self.overlay.set_state(
                    OverlayState.LOADING, "Loading model..."
                )
            )
            return
        if self._busy:
            return
        try:
            self.recorder.start()
            self._idle_add(lambda: self.overlay.set_state(OverlayState.RECORDING))
        except Exception as exc:
            logger.exception("Failed to start recording")
            self._idle_add(lambda: self.overlay.set_error(f"Mic error: {exc}"))

    def on_hotkey_release(self) -> None:
        if self._busy or not self.recorder.is_recording:
            return
        try:
            audio = self.recorder.stop()
        except Exception as exc:
            logger.exception("Failed to stop recording")
            self._idle_add(lambda: self.overlay.set_error(f"Mic error: {exc}"))
            return

        duration = audio.size / SAMPLE_RATE if audio.size else 0.0
        if duration < MIN_RECORDING_SECONDS:
            self._idle_add(lambda: self.overlay.set_state(OverlayState.IDLE))
            return

        self._busy = True
        self._idle_add(lambda: self.overlay.set_state(OverlayState.TRANSCRIBING))
        threading.Thread(
            target=self._transcribe_and_copy,
            args=(audio,),
            daemon=True,
        ).start()

    def _transcribe_and_copy(self, audio: np.ndarray) -> None:
        try:
            text = self.transcriber.transcribe(audio)
            if text:
                self.clipboard.copy(text)
                if self.settings.auto_paste:
                    try:
                        self.clipboard.paste()
                    except Exception as exc:
                        logger.warning("Auto-paste failed: %s", exc)
                self._idle_add(self._on_success)
            else:
                self._idle_add(
                    lambda: self.overlay.set_state(
                        OverlayState.ERROR, "No speech detected"
                    )
                )
        except Exception as exc:
            logger.exception("Transcription failed")
            self._idle_add(lambda: self._on_error(str(exc)))
        finally:
            self._busy = False

    def _on_success(self) -> None:
        if self.settings.auto_paste:
            self.overlay.set_state(OverlayState.DONE, "Copied & pasted!")
        else:
            self.overlay.set_state(OverlayState.DONE)

    def apply_settings(self, settings: UserSettings) -> None:
        if self.recorder.is_recording:
            self.overlay.set_error("Cannot change settings while recording")
            return
        try:
            self.recorder.set_input_device(settings.input_device)
        except RuntimeError as exc:
            self.overlay.set_error(str(exc))
            return
        self.settings = settings
        self.settings_store.save(settings)
        logger.info(
            "Settings saved (input_device=%s, auto_paste=%s)",
            settings.input_device,
            settings.auto_paste,
        )

    def open_settings(self) -> None:
        def show_dialog() -> bool:
            SettingsDialog(
                settings=self.settings,
                on_save=self.apply_settings,
            ).show()
            return False

        self._idle_add(show_dialog)

    AUDIO_FILE_FILTER_PATTERNS = [
        "*.wav", "*.mp3", "*.flac", "*.ogg", "*.opus", "*.m4a",
        "*.wma", "*.aac", "*.webm", "*.mp4",
    ]

    def on_file_transcribe(self) -> None:
        if not self._model_ready:
            self._idle_add(
                lambda: self.overlay.set_state(
                    OverlayState.LOADING, "Loading model..."
                )
            )
            return
        if self._busy or self._file_dialog_open:
            return
        self._file_dialog_open = True
        self._idle_add(self._show_file_picker)

    def _present_file_dialog(self, dialog) -> None:
        """Raise the file picker above other windows and give it keyboard focus."""
        Gtk = self._gtk
        dialog.set_modal(True)
        dialog.set_keep_above(True)
        if Gtk is not None:
            dialog.set_position(Gtk.WindowPosition.CENTER)
        dialog.set_accept_focus(True)
        dialog.set_focus_on_map(True)
        dialog.show_all()
        dialog.present()

    def _show_file_picker(self) -> bool:
        import gi

        gi.require_version("Gtk", "3.0")
        from gi.repository import Gdk, Gtk

        if self._gtk is None:
            self._gtk = Gtk

        dialog = Gtk.FileChooserDialog(
            title="Select audio file to transcribe",
            action=Gtk.FileChooserAction.OPEN,
        )
        dialog.set_type_hint(Gdk.WindowTypeHint.DIALOG)
        dialog.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dialog.add_button("Transcribe", Gtk.ResponseType.OK)

        audio_filter = Gtk.FileFilter()
        audio_filter.set_name("Audio files")
        for pattern in self.AUDIO_FILE_FILTER_PATTERNS:
            audio_filter.add_pattern(pattern)
        dialog.add_filter(audio_filter)

        all_filter = Gtk.FileFilter()
        all_filter.set_name("All files")
        all_filter.add_pattern("*")
        dialog.add_filter(all_filter)

        filepath = None
        response = Gtk.ResponseType.CANCEL
        try:
            self._present_file_dialog(dialog)
            response = dialog.run()
            if response == Gtk.ResponseType.OK:
                filepath = dialog.get_filename()
        finally:
            dialog.destroy()
            self._file_dialog_open = False

        if response == Gtk.ResponseType.OK and filepath:
            self._busy = True
            self._idle_add(
                lambda: self.overlay.set_state(
                    OverlayState.TRANSCRIBING, "Transcribing file..."
                )
            )
            threading.Thread(
                target=self._transcribe_file_and_copy,
                args=(filepath,),
                daemon=True,
            ).start()
        return False

    def _transcribe_file_and_copy(self, filepath: str) -> None:
        try:
            text = self.transcriber.transcribe_file(filepath)
            if text:
                self.clipboard.copy(text)
                if self.settings.auto_paste:
                    try:
                        self.clipboard.paste()
                    except Exception as exc:
                        logger.warning("Auto-paste failed: %s", exc)
                self._idle_add(self._on_success)
            else:
                self._idle_add(
                    lambda: self.overlay.set_state(
                        OverlayState.ERROR, "No speech in file"
                    )
                )
        except Exception as exc:
            logger.exception("File transcription failed")
            self._idle_add(lambda: self._on_error(str(exc)))
        finally:
            self._busy = False

    def _on_error(self, message: str) -> None:
        self.overlay.set_error(message[:80])

    def _load_model(self) -> None:
        try:
            self.transcriber.load()
            self._model_ready = True
            if self._socket_server is None:
                self._socket_server = TranscriptionSocketServer(self.transcriber)
            self._socket_server.start()
            self._idle_add(lambda: self.overlay.set_state(OverlayState.IDLE))
        except Exception as exc:
            logger.exception("Failed to load model")
            self._idle_add(lambda: self.overlay.set_error(f"Model error: {exc}"))

    def request_quit(self) -> None:
        self._should_quit = True
        if self._gtk is not None:
            self._gtk.main_quit()

    def run(self) -> int:
        import gi

        gi.require_version("Gtk", "3.0")
        from gi.repository import GLib, Gtk

        self._gtk = Gtk
        self._glib = GLib

        if self.hotkeys is None:
            self.hotkeys = HotkeyDetector(
                on_press=self.on_hotkey_press,
                on_release=self.on_hotkey_release,
                on_quit=self.request_quit,
            )

        self.overlay.build()
        self.overlay.set_state(OverlayState.LOADING)
        if self.tray is None:
            self.tray = TrayIcon(
                on_quit=self.request_quit,
                on_settings=self.open_settings,
                on_file_transcribe=self.on_file_transcribe,
            )
        self.tray.build()
        self.hotkeys.start()
        threading.Thread(target=self._load_model, daemon=True).start()

        try:
            Gtk.main()
        except KeyboardInterrupt:
            pass
        finally:
            self.hotkeys.stop()
            if self.recorder.is_recording:
                self.recorder.stop()
            if self._socket_server is not None:
                self._socket_server.stop()
            if self.tray is not None:
                self.tray.destroy()

        return 0


LOCK_PATH = os.path.join(
    os.environ.get("XDG_RUNTIME_DIR", "/tmp"),
    "press-to-talk.lock",
)
TRANSCRIBE_SOCKET_PATH = Path.home() / ".cache" / "press-to-talk" / "transcribe.sock"


class TranscriptionSocketServer:
    """Unix socket so ZapZap (Flatpak) can request transcription from this app."""

    def __init__(self, transcriber: Transcriber) -> None:
        self._transcriber = transcriber
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._serve, name="ptt-socket", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                client.settimeout(0.5)
                client.connect(str(TRANSCRIBE_SOCKET_PATH))
                client.sendall(b'{"cmd":"shutdown"}\n')
        except OSError:
            pass
        if self._thread:
            self._thread.join(timeout=2.0)

    def _serve(self) -> None:
        import socket as socket_mod

        TRANSCRIBE_SOCKET_PATH.parent.mkdir(parents=True, exist_ok=True)
        try:
            TRANSCRIBE_SOCKET_PATH.unlink(missing_ok=True)
        except OSError:
            pass

        server = socket_mod.socket(socket_mod.AF_UNIX, socket_mod.SOCK_STREAM)
        server.bind(str(TRANSCRIBE_SOCKET_PATH))
        server.listen(4)
        server.settimeout(1.0)
        logger.info("ZapZap transcription socket: %s", TRANSCRIBE_SOCKET_PATH)

        while not self._stop.is_set():
            try:
                conn, _addr = server.accept()
            except TimeoutError:
                continue
            except OSError:
                break
            threading.Thread(
                target=self._handle_client,
                args=(conn,),
                daemon=True,
            ).start()

        try:
            server.close()
        except OSError:
            pass
        try:
            TRANSCRIBE_SOCKET_PATH.unlink(missing_ok=True)
        except OSError:
            pass

    def _handle_client(self, conn) -> None:
        import socket as socket_mod

        with conn:
            data = b""
            while b"\n" not in data:
                try:
                    chunk = conn.recv(65536)
                except socket_mod.timeout:
                    break
                if not chunk:
                    break
                data += chunk
            if not data:
                return
            line = data.split(b"\n", 1)[0].decode("utf-8", errors="replace")
            try:
                request = json.loads(line)
                if request.get("cmd") == "shutdown":
                    return
                path = request.get("path")
                if not path or not os.path.isfile(path):
                    raise ValueError(f"audio file not found: {path!r}")
                with self._lock:
                    text = self._transcriber.transcribe_file(path)
                response = {"text": text, "error": None}
            except Exception as exc:
                logger.exception("Socket transcription failed")
                response = {"text": "", "error": str(exc)}
            try:
                conn.sendall((json.dumps(response) + "\n").encode("utf-8"))
            except OSError:
                pass


def _acquire_lock() -> int | None:
    """Acquire an exclusive lock file. Returns the fd or None if another
    instance is already running (kills it first and retries)."""
    fd = os.open(LOCK_PATH, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        existing = os.read(fd, 32).strip()
        if existing:
            old_pid = int(existing)
            logger.info("Killing existing instance (pid %d)", old_pid)
            try:
                os.kill(old_pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        os.close(fd)
        import time
        time.sleep(0.3)
        fd = os.open(LOCK_PATH, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            return None
    os.ftruncate(fd, 0)
    os.lseek(fd, 0, os.SEEK_SET)
    os.write(fd, str(os.getpid()).encode())
    return fd


def run_transcribe_file(config: AppConfig, path: str) -> int:
    """Transcribe a single file and print text to stdout (for ZapZap bridge)."""
    if not os.path.isfile(path):
        print(f"File not found: {path}", file=sys.stderr)
        return 1
    transcriber = Transcriber(config)
    try:
        transcriber.load()
        text = transcriber.transcribe_file(path)
    except Exception as exc:
        print(f"Transcription failed: {exc}", file=sys.stderr)
        return 1
    print(text)
    return 0


def run_serve(config: AppConfig) -> int:
    """Headless socket server: load model once, serve transcription requests."""
    transcriber = Transcriber(config)
    try:
        transcriber.load()
    except Exception as exc:
        print(f"Model load failed: {exc}", file=sys.stderr)
        return 1
    logger.info("Model loaded, starting socket server…")
    server = TranscriptionSocketServer(transcriber)
    server._stop = threading.Event()

    def _on_signal(signum, frame):
        server._stop.set()

    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)

    server._serve()
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        config, check_only, transcribe_file, serve = parse_args(argv)
    except SystemExit:
        raise
    except ValueError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2

    if transcribe_file:
        return run_transcribe_file(config, transcribe_file)

    if serve:
        return run_serve(config)

    transcriber = Transcriber(config)
    if check_only:
        try:
            transcriber.load()
            print(
                f"OK: loaded model '{config.model}' on device '{config.device}' "
                f"with compute_type '{config.compute_type}'"
            )
            return 0
        except Exception as exc:
            print(f"Check failed: {exc}", file=sys.stderr)
            return 1

    lock_fd = _acquire_lock()
    if lock_fd is None:
        print("Another instance is already running and could not be replaced.", file=sys.stderr)
        return 1

    def _release_lock():
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
            os.close(lock_fd)
            os.unlink(LOCK_PATH)
        except OSError:
            pass

    atexit.register(_release_lock)

    app = PressToTalkApp(config=config, transcriber=transcriber)
    return app.run()


if __name__ == "__main__":
    raise SystemExit(main())
