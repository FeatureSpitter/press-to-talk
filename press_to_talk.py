#!/usr/bin/env python3
"""
Press-to-Talk Local Transcription Tool

Hold Ctrl+M to record speech. Release to transcribe locally with faster-whisper
and copy the result to the clipboard. A small Gtk overlay shows status without
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
import logging
import os
import select
import shutil
import signal
import subprocess
import sys
import threading
from dataclasses import dataclass
from enum import Enum
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
        description="Hold Ctrl+M to record, release to transcribe and copy to clipboard."
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
    return config, args.check


class AudioRecorder:
    """Capture mono 16 kHz float32 audio while recording is active."""

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
        self._chunks: list[np.ndarray] = []
        self._lock = threading.Lock()

    def _audio_callback(self, indata, frames, time_info, status) -> None:
        if status:
            logger.warning("Audio status: %s", status)
        with self._lock:
            self._chunks.append(indata.copy())

    def start(self) -> None:
        if self._stream is not None:
            return
        self._chunks = []
        self._stream = self._sd.InputStream(
            samplerate=self.sample_rate,
            channels=self.channels,
            dtype="float32",
            callback=self._audio_callback,
        )
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

    def transcribe(self, audio: np.ndarray) -> str:
        if self._model is None:
            raise RuntimeError("Model is not loaded")
        if audio.size == 0:
            return ""

        kwargs = {
            "beam_size": self.config.beam_size,
            "vad_filter": True,
            "condition_on_previous_text": False,
        }
        if self.config.language:
            kwargs["language"] = self.config.language

        segments, _info = self._model.transcribe(audio, **kwargs)
        return "".join(segment.text for segment in segments).strip()


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


class HotkeyDetector:
    """Detect hold-to-talk for Ctrl+M (left or right Ctrl).

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
        self._combo_active = False
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

    def _update_combo(self) -> None:
        should_be_active = self._ctrl_held and self._m_held
        if should_be_active and not self._combo_active:
            self._combo_active = True
            self._on_press()
        elif not should_be_active and self._combo_active:
            self._combo_active = False
            self._on_release()

    def handle_press(self, key) -> None:
        with self._lock:
            if self._is_ctrl(key):
                self._ctrl_held = True
            elif self._is_m(key):
                self._m_held = True
            elif self._ctrl_held and self._is_q(key) and self._on_quit:
                self._on_quit()
                return
            self._update_combo()

    def handle_release(self, key) -> None:
        with self._lock:
            if self._is_ctrl(key):
                self._ctrl_held = False
            elif self._is_m(key):
                self._m_held = False
            self._update_combo()

    @staticmethod
    def _control_modifier_masks() -> list[int]:
        import Xlib.X

        ctrl = Xlib.X.ControlMask
        lock = Xlib.X.LockMask
        mod2 = Xlib.X.Mod2Mask
        return [ctrl, ctrl | lock, ctrl | mod2, ctrl | lock | mod2]

    def _x11_grab_combo_keys(self, display, grab_win) -> tuple[set[int], int, int]:
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
            "X11 key suppression active: Ctrl and Ctrl+M are blocked for other apps"
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
        logger.info("Hotkey listener active (hold Ctrl+M)")

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


class TrayIcon:
    """System tray icon with a Quit menu item."""

    ICON_NAME = "audio-input-microphone"
    TOOLTIP = "Press to Talk\nHold Ctrl+M to record, release to copy"

    def __init__(
        self,
        on_quit: Callable[[], None],
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
        self._indicator = None
        self._status_icon = None
        self._menu = None
        self._built = False

    def _build_menu(self):
        Gtk = self._Gtk
        menu = Gtk.Menu()

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
        logger.info("Tray icon ready (right-click for Quit)")

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
        glib_module=None,
    ) -> None:
        self.config = config
        self.overlay = overlay or StatusOverlay()
        self.recorder = recorder or AudioRecorder()
        self.transcriber = transcriber or Transcriber(config)
        self.clipboard = clipboard or ClipboardManager(config.xclip_path)
        self.hotkeys = hotkeys
        self.tray = tray
        self._glib = glib_module
        self._busy = False
        self._model_ready = False
        self._should_quit = False
        self._gtk = None

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
        self.overlay.set_state(OverlayState.DONE)

    def _on_error(self, message: str) -> None:
        self.overlay.set_error(message[:80])

    def _load_model(self) -> None:
        try:
            self.transcriber.load()
            self._model_ready = True
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
            self.tray = TrayIcon(on_quit=self.request_quit)
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
            if self.tray is not None:
                self.tray.destroy()

        return 0


LOCK_PATH = os.path.join(
    os.environ.get("XDG_RUNTIME_DIR", "/tmp"),
    "press-to-talk.lock",
)


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


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        config, check_only = parse_args(argv)
    except SystemExit:
        raise
    except ValueError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2

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
