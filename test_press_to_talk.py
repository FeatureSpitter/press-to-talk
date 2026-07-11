"""Comprehensive tests for press_to_talk.py with mocked hardware."""

from __future__ import annotations

import subprocess
import threading
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

import press_to_talk as ptt


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def config() -> ptt.AppConfig:
    return ptt.AppConfig(
        model="large-v3-turbo",
        device="cuda",
        compute_type="float16",
        language=None,
        beam_size=5,
        xclip_path="xclip",
    )


@pytest.fixture
def mock_whisper_model():
    segment = SimpleNamespace(text=" Olá mundo")
    model = MagicMock()
    model.transcribe.return_value = ([segment], SimpleNamespace(language="pt"))
    return model


@pytest.fixture
def mock_sounddevice():
    captured = {"callback": None, "active": False, "chunks": []}

    class FakeStream:
        def __init__(self, samplerate, channels, dtype, callback):
            captured["samplerate"] = samplerate
            captured["channels"] = channels
            captured["dtype"] = dtype
            captured["callback"] = callback

        def start(self):
            captured["active"] = True

        def stop(self):
            captured["active"] = False

        def close(self):
            captured["active"] = False

    def emit_chunk(data):
        if captured["callback"]:
            captured["callback"](data, len(data), None, None)

    sd = MagicMock()
    sd.InputStream.side_effect = lambda **kwargs: FakeStream(**kwargs)
    sd.emit_chunk = emit_chunk
    sd.captured = captured
    return sd


@pytest.fixture
def mock_gtk():
    calls = {"states": [], "hidden": False, "texts": []}

    class FakeLabel:
        def set_text(self, text):
            calls["texts"].append(text)

        def set_margin_start(self, *_):
            pass

        def set_margin_end(self, *_):
            pass

        def set_margin_top(self, *_):
            pass

        def set_margin_bottom(self, *_):
            pass

    class FakeEventBox:
        def add(self, _):
            pass

        def get_style_context(self):
            ctx = MagicMock()
            ctx.add_class = MagicMock()
            ctx.add_provider = MagicMock()
            return ctx

    class FakeWindow:
        def __init__(self, **_):
            self.visible = False

        def set_title(self, *_):
            pass

        def set_decorated(self, *_):
            pass

        def set_resizable(self, *_):
            pass

        def set_keep_above(self, *_):
            pass

        def set_skip_taskbar_hint(self, *_):
            pass

        def set_skip_pager_hint(self, *_):
            pass

        def set_accept_focus(self, *_):
            pass

        def set_focus_on_map(self, *_):
            pass

        def set_type_hint(self, *_):
            pass

        def set_default_size(self, *_):
            pass

        def set_visual(self, *_):
            pass

        def add(self, _):
            pass

        def connect(self, *_):
            pass

        def move(self, *_):
            pass

        def show_all(self):
            self.visible = True

        def hide(self):
            self.visible = False
            calls["hidden"] = True

    class FakeCssProvider:
        def load_from_data(self, _):
            pass

    class FakeMonitor:
        def get_workarea(self):
            return SimpleNamespace(x=0, y=0, width=1920, height=1080)

    class FakeDisplay:
        def get_primary_monitor(self):
            return FakeMonitor()

    class FakeScreen:
        def get_rgba_visual(self):
            return object()

        def is_composited(self):
            return True

    class FakeGdk:
        Display = MagicMock()
        Display.get_default.return_value = FakeDisplay()
        Screen = MagicMock()
        Screen.get_default.return_value = FakeScreen()
        WindowTypeHint = SimpleNamespace(NOTIFICATION="notification")

    class FakeGtk:
        WindowType = SimpleNamespace(TOPLEVEL="toplevel")
        Window = FakeWindow
        Label = FakeLabel
        EventBox = FakeEventBox
        CssProvider = FakeCssProvider
        STYLE_PROVIDER_PRIORITY_APPLICATION = 600

    class FakeGLib:
        _timers = []

        @staticmethod
        def idle_add(callback):
            callback()
            return 1

        @staticmethod
        def source_remove(_):
            pass

        @staticmethod
        def timeout_add(_ms, callback):
            FakeGLib._timers.append(callback)
            return len(FakeGLib._timers)

    overlay = ptt.StatusOverlay(gtk_modules=(FakeGtk, FakeGdk, FakeGLib))
    overlay._record_call = calls
    return overlay, FakeGLib


@pytest.fixture
def mock_xclip(monkeypatch):
    copied = {"text": None}

    def fake_run(cmd, input=None, check=True):
        copied["cmd"] = cmd
        copied["text"] = input.decode("utf-8")
        return MagicMock(returncode=0)

    monkeypatch.setattr("shutil.which", lambda path: path if path == "xclip" else None)
    monkeypatch.setattr(subprocess, "run", fake_run)
    return copied


@pytest.fixture
def mock_pynput_key():
    class Key:
        ctrl = "ctrl"
        ctrl_l = "ctrl_l"
        ctrl_r = "ctrl_r"

    class KeyCode:
        def __init__(self, char):
            self.char = char

    return Key, KeyCode


# ---------------------------------------------------------------------------
# Config / CLI
# ---------------------------------------------------------------------------


class TestConfig:
    def test_defaults(self):
        config, check = ptt.parse_args([])
        assert config.model == "large-v3-turbo"
        assert config.device == "cuda"
        assert config.compute_type == "float16"
        assert config.language is None
        assert config.beam_size == 5
        assert check is False

    def test_language_pt(self):
        config, _ = ptt.parse_args(["--language", "pt"])
        assert config.language == "pt"

    def test_language_en(self):
        config, _ = ptt.parse_args(["--language", "en"])
        assert config.language == "en"

    def test_invalid_language_rejected(self):
        with pytest.raises(ValueError, match="Unsupported language"):
            ptt.parse_args(["--language", "fr"])

    def test_custom_model_and_device(self):
        config, _ = ptt.parse_args(
            ["--model", "large-v3", "--device", "cpu", "--beam-size", "3"]
        )
        assert config.model == "large-v3"
        assert config.device == "cpu"
        assert config.beam_size == 3

    def test_check_flag(self):
        _, check = ptt.parse_args(["--check"])
        assert check is True

    def test_validate_rejects_bad_language(self, config):
        bad = ptt.AppConfig(language="de")
        with pytest.raises(ValueError, match="Unsupported language"):
            bad.validate()

    def test_validate_rejects_bad_beam_size(self, config):
        bad = ptt.AppConfig(beam_size=0)
        with pytest.raises(ValueError, match="beam_size"):
            bad.validate()


# ---------------------------------------------------------------------------
# AudioRecorder
# ---------------------------------------------------------------------------


class TestAudioRecorder:
    def test_start_stop_empty_buffer(self, mock_sounddevice):
        recorder = ptt.AudioRecorder(sounddevice_module=mock_sounddevice)
        recorder.start()
        audio = recorder.stop()
        assert audio.dtype == np.float32
        assert audio.size == 0
        assert mock_sounddevice.captured["samplerate"] == 16_000
        assert mock_sounddevice.captured["channels"] == 1
        assert mock_sounddevice.captured["dtype"] == "float32"

    def test_buffer_accumulation(self, mock_sounddevice):
        recorder = ptt.AudioRecorder(sounddevice_module=mock_sounddevice)
        recorder.start()
        chunk = np.array([[0.1], [0.2], [0.3]], dtype=np.float32)
        mock_sounddevice.emit_chunk(chunk)
        mock_sounddevice.emit_chunk(chunk)
        audio = recorder.stop()
        assert audio.shape == (6,)
        np.testing.assert_allclose(audio[:3], [0.1, 0.2, 0.3])

    def test_buffer_reset_between_recordings(self, mock_sounddevice):
        recorder = ptt.AudioRecorder(sounddevice_module=mock_sounddevice)
        recorder.start()
        mock_sounddevice.emit_chunk(np.array([[1.0]], dtype=np.float32))
        recorder.stop()
        recorder.start()
        audio = recorder.stop()
        assert audio.size == 0

    def test_is_recording_flag(self, mock_sounddevice):
        recorder = ptt.AudioRecorder(sounddevice_module=mock_sounddevice)
        assert recorder.is_recording is False
        recorder.start()
        assert recorder.is_recording is True
        recorder.stop()
        assert recorder.is_recording is False

    def test_double_start_is_idempotent(self, mock_sounddevice):
        recorder = ptt.AudioRecorder(sounddevice_module=mock_sounddevice)
        recorder.start()
        recorder.start()
        assert mock_sounddevice.InputStream.call_count == 1


# ---------------------------------------------------------------------------
# Transcriber
# ---------------------------------------------------------------------------


class TestTranscriber:
    def test_load_uses_config(self, config):
        with patch("faster_whisper.WhisperModel") as whisper_cls:
            transcriber = ptt.Transcriber(config)
            transcriber.load()
            whisper_cls.assert_called_once_with(
                "large-v3-turbo",
                device="cuda",
                compute_type="float16",
            )

    def test_transcribe_empty_audio(self, config, mock_whisper_model):
        transcriber = ptt.Transcriber(config, whisper_model=mock_whisper_model)
        assert transcriber.transcribe(np.array([], dtype=np.float32)) == ""
        mock_whisper_model.transcribe.assert_not_called()

    def test_transcribe_calls_model_with_accuracy_params(
        self, config, mock_whisper_model
    ):
        transcriber = ptt.Transcriber(config, whisper_model=mock_whisper_model)
        audio = np.zeros(16_000, dtype=np.float32)
        text = transcriber.transcribe(audio)
        assert text == "Olá mundo"
        mock_whisper_model.transcribe.assert_called_once()
        _, kwargs = mock_whisper_model.transcribe.call_args
        assert kwargs["beam_size"] == 5
        assert kwargs["vad_filter"] is True
        assert kwargs["condition_on_previous_text"] is False
        assert "language" not in kwargs

    def test_transcribe_forced_language(self, config, mock_whisper_model):
        config = ptt.AppConfig(language="pt")
        transcriber = ptt.Transcriber(config, whisper_model=mock_whisper_model)
        audio = np.zeros(16_000, dtype=np.float32)
        transcriber.transcribe(audio)
        _, kwargs = mock_whisper_model.transcribe.call_args
        assert kwargs["language"] == "pt"

    def test_transcribe_joins_multiple_segments(self, config):
        segments = [
            SimpleNamespace(text="Hello "),
            SimpleNamespace(text="world"),
        ]
        model = MagicMock()
        model.transcribe.return_value = (segments, None)
        transcriber = ptt.Transcriber(config, whisper_model=model)
        text = transcriber.transcribe(np.zeros(100, dtype=np.float32))
        assert text == "Hello world"

    def test_transcribe_requires_loaded_model(self, config):
        transcriber = ptt.Transcriber(config)
        with pytest.raises(RuntimeError, match="not loaded"):
            transcriber.transcribe(np.zeros(10, dtype=np.float32))


# ---------------------------------------------------------------------------
# Clipboard
# ---------------------------------------------------------------------------


class TestClipboard:
    def test_copy_text(self, mock_xclip):
        cb = ptt.ClipboardManager()
        cb.copy("Hello clipboard")
        assert mock_xclip["text"] == "Hello clipboard"
        assert mock_xclip["cmd"] == ["xclip", "-selection", "clipboard"]

    def test_empty_text_not_copied(self, mock_xclip):
        cb = ptt.ClipboardManager()
        cb.copy("")
        assert mock_xclip["text"] is None

    def test_unicode_pt_characters(self, mock_xclip):
        cb = ptt.ClipboardManager()
        text = 'Ação "ção" — não'
        cb.copy(text)
        assert mock_xclip["text"] == text

    def test_newlines_preserved(self, mock_xclip):
        cb = ptt.ClipboardManager()
        cb.copy("line1\nline2")
        assert mock_xclip["text"] == "line1\nline2"

    def test_missing_xclip_raises(self, monkeypatch):
        monkeypatch.setattr("shutil.which", lambda _: None)
        cb = ptt.ClipboardManager(xclip_path="xclip")
        with pytest.raises(FileNotFoundError, match="xclip not found"):
            cb.copy("test")


# ---------------------------------------------------------------------------
# Hotkey detection
# ---------------------------------------------------------------------------


class TestHotkeyDetector:
    def _make_detector(self, mock_pynput_key):
        Key, KeyCode = mock_pynput_key
        presses = []
        releases = []

        class FakeListener:
            def __init__(self, on_press, on_release):
                self.on_press = on_press
                self.on_release = on_release

            def start(self):
                pass

            def stop(self):
                pass

        detector = ptt.HotkeyDetector(
            on_press=lambda: presses.append(True),
            on_release=lambda: releases.append(True),
            keyboard_listener_cls=FakeListener,
            key_cls=Key,
            use_x11_grab=False,
        )
        return detector, presses, releases, Key, KeyCode

    def test_ctrl_m_press_triggers_recording(self, mock_pynput_key):
        detector, presses, releases, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(Key.ctrl_l)
        detector.handle_press(KeyCode("m"))
        assert len(presses) == 1
        assert len(releases) == 0

    def test_release_ctrl_stops_recording(self, mock_pynput_key):
        detector, presses, releases, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(Key.ctrl_r)
        detector.handle_press(KeyCode("M"))
        detector.handle_release(Key.ctrl_r)
        assert len(presses) == 1
        assert len(releases) == 1

    def test_release_m_keeps_recording(self, mock_pynput_key):
        detector, presses, releases, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(Key.ctrl_l)
        detector.handle_press(KeyCode("m"))
        detector.handle_release(KeyCode("m"))
        assert len(presses) == 1
        assert len(releases) == 0
        detector.handle_release(Key.ctrl_l)
        assert len(releases) == 1

    def test_right_ctrl_works(self, mock_pynput_key):
        detector, presses, _, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(Key.ctrl_r)
        detector.handle_press(KeyCode("m"))
        assert len(presses) == 1

    def test_other_keys_ignored(self, mock_pynput_key):
        detector, presses, releases, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(KeyCode("a"))
        detector.handle_press(KeyCode("b"))
        assert presses == []
        assert releases == []

    def test_debounce_no_double_press(self, mock_pynput_key):
        detector, presses, _, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(Key.ctrl_l)
        detector.handle_press(KeyCode("m"))
        detector.handle_press(KeyCode("m"))  # key repeat
        assert len(presses) == 1

    def test_rapid_press_release(self, mock_pynput_key):
        detector, presses, releases, Key, KeyCode = self._make_detector(mock_pynput_key)
        detector.handle_press(Key.ctrl_l)
        detector.handle_press(KeyCode("m"))
        detector.handle_release(Key.ctrl_l)
        detector.handle_press(Key.ctrl_l)
        detector.handle_press(KeyCode("m"))
        assert len(presses) == 2
        assert len(releases) == 1

    def test_ctrl_q_triggers_quit(self, mock_pynput_key):
        Key, KeyCode = mock_pynput_key
        quit_called = []

        class FakeListener:
            def __init__(self, on_press, on_release):
                pass

            def start(self):
                pass

            def stop(self):
                pass

        detector = ptt.HotkeyDetector(
            on_press=lambda: None,
            on_release=lambda: None,
            on_quit=lambda: quit_called.append(True),
            keyboard_listener_cls=FakeListener,
            key_cls=Key,
            use_x11_grab=False,
        )
        detector.handle_press(Key.ctrl_l)
        detector.handle_press(KeyCode("q"))
        assert len(quit_called) == 1

    def test_start_always_enables_pynput_listener(self, mock_pynput_key):
        started = []

        class FakeListener:
            def __init__(self, on_press, on_release):
                pass

            def start(self):
                started.append(True)

            def stop(self):
                pass

        Key, _ = mock_pynput_key
        detector = ptt.HotkeyDetector(
            on_press=lambda: None,
            on_release=lambda: None,
            keyboard_listener_cls=FakeListener,
            key_cls=Key,
            use_x11_grab=False,
        )
        detector.start()
        assert started == [True]


# ---------------------------------------------------------------------------
# Overlay state machine
# ---------------------------------------------------------------------------


class TestOverlay:
    def test_idle_hides_window(self, mock_gtk):
        overlay, _ = mock_gtk
        overlay.build()
        overlay.set_state(ptt.OverlayState.RECORDING)
        overlay.set_state(ptt.OverlayState.IDLE)
        assert overlay.state == ptt.OverlayState.IDLE
        assert overlay._record_call["hidden"] is True

    def test_recording_shows_message(self, mock_gtk):
        overlay, _ = mock_gtk
        overlay.set_state(ptt.OverlayState.RECORDING)
        assert overlay.state == ptt.OverlayState.RECORDING
        assert "Recording" in overlay._record_call["texts"][-1]

    def test_transcribing_shows_message(self, mock_gtk):
        overlay, _ = mock_gtk
        overlay.set_state(ptt.OverlayState.TRANSCRIBING)
        assert "Transcribing" in overlay._record_call["texts"][-1]

    def test_done_shows_copied(self, mock_gtk):
        overlay, _ = mock_gtk
        overlay.set_state(ptt.OverlayState.DONE)
        assert "Copied" in overlay._record_call["texts"][-1]

    def test_error_custom_message(self, mock_gtk):
        overlay, _ = mock_gtk
        overlay.set_error("Mic error: unavailable")
        assert overlay.state == ptt.OverlayState.ERROR
        assert overlay._record_call["texts"][-1] == "Mic error: unavailable"

    def test_state_transitions(self, mock_gtk):
        overlay, _ = mock_gtk
        for state in [
            ptt.OverlayState.LOADING,
            ptt.OverlayState.RECORDING,
            ptt.OverlayState.TRANSCRIBING,
            ptt.OverlayState.DONE,
            ptt.OverlayState.IDLE,
        ]:
            overlay.set_state(state)
            if state == ptt.OverlayState.IDLE:
                assert overlay.state == ptt.OverlayState.IDLE
            else:
                assert overlay.state == state


# ---------------------------------------------------------------------------
# Tray icon
# ---------------------------------------------------------------------------


class TestTrayIcon:
    def test_quit_menu_item_calls_callback(self):
        quit_called = []

        class FakeMenuItem:
            def __init__(self, label=""):
                self.label = label
                self._handler = None

            def connect(self, signal, handler):
                self._handler = handler

            def show(self):
                pass

        class FakeMenu:
            def __init__(self):
                self.items = []

            def append(self, item):
                self.items.append(item)

            def show_all(self):
                pass

        class FakeIndicator:
            IndicatorCategory = SimpleNamespace(APPLICATION_STATUS=0)
            IndicatorStatus = SimpleNamespace(ACTIVE=0, PASSIVE=1)

            class Indicator:
                @staticmethod
                def new(*_):
                    indicator = MagicMock()
                    indicator.set_status = MagicMock()
                    indicator.set_icon_full = MagicMock()
                    indicator.set_title = MagicMock()
                    indicator.set_menu = MagicMock()
                    return indicator

        class FakeGtk:
            Menu = FakeMenu
            MenuItem = FakeMenuItem

            @staticmethod
            def get_current_event_time():
                return 0

        tray = ptt.TrayIcon(
            on_quit=lambda: quit_called.append(True),
            gtk_modules=(FakeGtk, FakeIndicator),
        )
        tray.build()
        quit_item = tray._menu.items[0]
        quit_item._handler(quit_item)
        assert quit_called == [True]


# ---------------------------------------------------------------------------
# Integration: PressToTalkApp
# ---------------------------------------------------------------------------


class TestPressToTalkApp:
    def _make_app(self, config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip):
        overlay, glib = mock_gtk
        transcriber = ptt.Transcriber(config, whisper_model=mock_whisper_model)
        transcriber._model = mock_whisper_model
        app = ptt.PressToTalkApp(
            config=config,
            overlay=overlay,
            recorder=ptt.AudioRecorder(sounddevice_module=mock_sounddevice),
            transcriber=transcriber,
            clipboard=ptt.ClipboardManager(),
            glib_module=glib,
        )
        app._model_ready = True
        return app

    def test_full_pipeline(
        self, config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
    ):
        app = self._make_app(
            config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
        )
        app.on_hotkey_press()
        chunk = np.ones((800,), dtype=np.float32)
        mock_sounddevice.emit_chunk(chunk.reshape(-1, 1))
        app.on_hotkey_release()

        # Wait for worker thread
        for _ in range(50):
            if not app._busy:
                break
            threading.Event().wait(0.01)

        assert mock_xclip["text"] == "Olá mundo"
        assert app.overlay.state in {
            ptt.OverlayState.DONE,
            ptt.OverlayState.IDLE,
        }

    def test_zero_length_recording_ignored(
        self, config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
    ):
        app = self._make_app(
            config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
        )
        app.on_hotkey_press()
        app.on_hotkey_release()
        assert mock_xclip["text"] is None
        assert app.overlay.state == ptt.OverlayState.IDLE

    def test_concurrent_recording_ignored_while_busy(
        self, config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
    ):
        app = self._make_app(
            config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
        )
        app._busy = True
        app.on_hotkey_press()
        assert app.recorder.is_recording is False

    def test_model_not_ready_ignored(
        self, config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
    ):
        app = self._make_app(
            config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
        )
        app._model_ready = False
        app.on_hotkey_press()
        assert app.recorder.is_recording is False

    def test_transcription_failure_shows_error(
        self, config, mock_sounddevice, mock_gtk, mock_xclip
    ):
        overlay, glib = mock_gtk
        model = MagicMock()
        model.transcribe.side_effect = RuntimeError("CUDA OOM")
        transcriber = ptt.Transcriber(config, whisper_model=model)
        app = ptt.PressToTalkApp(
            config=config,
            overlay=overlay,
            recorder=ptt.AudioRecorder(sounddevice_module=mock_sounddevice),
            transcriber=transcriber,
            clipboard=ptt.ClipboardManager(),
            glib_module=glib,
        )
        app._model_ready = True
        app.on_hotkey_press()
        mock_sounddevice.emit_chunk(np.ones((800, 1), dtype=np.float32))
        app.on_hotkey_release()

        for _ in range(50):
            if not app._busy:
                break
            threading.Event().wait(0.01)

        assert app.overlay.state == ptt.OverlayState.ERROR

    def test_mic_error_on_start(
        self, config, mock_gtk, mock_whisper_model, mock_xclip
    ):
        overlay, glib = mock_gtk
        broken_sd = MagicMock()
        broken_sd.InputStream.side_effect = OSError("No microphone")
        transcriber = ptt.Transcriber(config, whisper_model=mock_whisper_model)
        app = ptt.PressToTalkApp(
            config=config,
            overlay=overlay,
            recorder=ptt.AudioRecorder(sounddevice_module=broken_sd),
            transcriber=transcriber,
            clipboard=ptt.ClipboardManager(),
            glib_module=glib,
        )
        app._model_ready = True
        app.on_hotkey_press()
        assert app.overlay.state == ptt.OverlayState.ERROR

    def test_empty_transcription_not_copied(
        self, config, mock_sounddevice, mock_gtk, mock_xclip
    ):
        overlay, glib = mock_gtk
        model = MagicMock()
        model.transcribe.return_value = ([], None)
        transcriber = ptt.Transcriber(config, whisper_model=model)
        app = ptt.PressToTalkApp(
            config=config,
            overlay=overlay,
            recorder=ptt.AudioRecorder(sounddevice_module=mock_sounddevice),
            transcriber=transcriber,
            clipboard=ptt.ClipboardManager(),
            glib_module=glib,
        )
        app._model_ready = True
        app.on_hotkey_press()
        mock_sounddevice.emit_chunk(np.ones((800, 1), dtype=np.float32))
        app.on_hotkey_release()

        for _ in range(50):
            if not app._busy:
                break
            threading.Event().wait(0.01)

        assert mock_xclip["text"] is None
        assert app.overlay.state == ptt.OverlayState.ERROR

    def test_long_recording_accepted(
        self, config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
    ):
        app = self._make_app(
            config, mock_sounddevice, mock_gtk, mock_whisper_model, mock_xclip
        )
        app.on_hotkey_press()
        long_chunk = np.ones((SAMPLE_RATE * 65, 1), dtype=np.float32)
        mock_sounddevice.emit_chunk(long_chunk)
        app.on_hotkey_release()

        for _ in range(100):
            if not app._busy:
                break
            threading.Event().wait(0.01)

        assert mock_xclip["text"] == "Olá mundo"


SAMPLE_RATE = ptt.SAMPLE_RATE


# ---------------------------------------------------------------------------
# Smoke test for --check entrypoint
# ---------------------------------------------------------------------------


class TestMain:
    def test_check_mode_success(self):
        with patch.object(ptt.Transcriber, "load") as load_mock:
            result = ptt.main(["--check"])
            assert result == 0
            load_mock.assert_called_once()

    def test_check_mode_failure(self):
        with patch.object(ptt.Transcriber, "load", side_effect=RuntimeError("no gpu")):
            result = ptt.main(["--check"])
            assert result == 1
