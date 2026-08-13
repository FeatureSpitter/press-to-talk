"""Transcribe WhatsApp voice messages via press-to-talk (host GPU)."""

from __future__ import annotations

import base64
import json
import os
import re
import socket
import subprocess
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from PyQt6.QtCore import QObject, pyqtSignal

LOG_PATH = Path.home() / ".cache" / "press-to-talk" / "zapzap.log"
SOCKET_PATH = Path.home() / ".cache" / "press-to-talk" / "transcribe.sock"
INCOMING_DIR = Path.home() / ".cache" / "press-to-talk" / "incoming"


def _inside_flatpak() -> bool:
    return bool(os.environ.get("FLATPAK_ID")) or os.path.isfile("/.flatpak-info")


def _default_ptt_dir() -> Path:
    env = os.environ.get("PRESS_TO_TALK_DIR")
    if env:
        return Path(env).expanduser()
    return Path.home() / "projectos" / "press-to-talk"


def _load_config() -> dict:
    config_path = Path.home() / ".config" / "press-to-talk" / "zapzap.json"
    if not config_path.is_file():
        return {}
    try:
        return json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def _write_log(message: str) -> None:
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with LOG_PATH.open("a", encoding="utf-8") as handle:
        handle.write(f"{stamp} {message}\n")


class VoiceTranscriptionService(QObject):
    """Download voice blobs from WA Web JS, transcribe on host, cache by message id."""

    _transcript_ready = pyqtSignal(str, str)

    CACHE_DIR = Path.home() / ".cache" / "press-to-talk" / "transcripts"

    def __init__(self, webview) -> None:
        super().__init__()
        self._webview = webview
        self._transcript_ready.connect(self._deliver)
        self._pending: set[str] = set()
        self._executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="ptt")
        self._config = _load_config()
        self.CACHE_DIR.mkdir(parents=True, exist_ok=True)
        INCOMING_DIR.mkdir(parents=True, exist_ok=True)
        _write_log("VoiceTranscriptionService initialized")

    def log(self, message: str) -> None:
        _write_log(f"JS: {message}")

    def _ptt_dir(self) -> Path:
        configured = self._config.get("press_to_talk_dir")
        if configured:
            return Path(configured).expanduser()
        return _default_ptt_dir()

    def _python_bin(self) -> Path:
        configured = self._config.get("python")
        if configured:
            return Path(configured).expanduser()
        venv_python = self._ptt_dir() / ".venv" / "bin" / "python"
        if venv_python.is_file():
            return venv_python
        return Path("/usr/bin/python3")

    def _script_path(self) -> Path:
        configured = self._config.get("script")
        if configured:
            return Path(configured).expanduser()
        return self._ptt_dir() / "press_to_talk.py"

    def _cache_path(self, msg_id: str) -> Path:
        safe = re.sub(r"[^\w\-.]", "_", msg_id)[:200]
        return self.CACHE_DIR / f"{safe}.txt"

    def get_cached_transcript(self, msg_id: str) -> str:
        path = self._cache_path(msg_id)
        if path.is_file():
            return path.read_text(encoding="utf-8")
        return ""

    def request_transcription(self, msg_id: str, base64_data: str) -> None:
        _write_log(f"request msg_id={msg_id[:40]}… bytes={len(base64_data)}")
        cached = self.get_cached_transcript(msg_id)
        if cached:
            _write_log(f"cache hit msg_id={msg_id[:40]}…")
            self._deliver(msg_id, cached)
            return
        if msg_id in self._pending:
            return
        self._pending.add(msg_id)
        self._executor.submit(self._transcribe_worker, msg_id, base64_data)

    def _transcribe_worker(self, msg_id: str, base64_data: str) -> None:
        text = ""
        try:
            audio = base64.b64decode(base64_data)
            if not audio:
                text = "(No speech detected)"
            else:
                safe_id = re.sub(r"[^\w\-.]", "_", msg_id)[:80]
                tmp_path = INCOMING_DIR / f"{safe_id}.ogg"
                tmp_path.write_bytes(audio)
                try:
                    text = self._run_transcriber(str(tmp_path))
                finally:
                    try:
                        tmp_path.unlink(missing_ok=True)
                    except OSError:
                        pass
            if not text.strip():
                text = "(No speech detected)"
            self._cache_path(msg_id).write_text(text, encoding="utf-8")
            _write_log(f"OK msg_id={msg_id[:40]}… len={len(text)}")
        except Exception as exc:
            _write_log(f"FAIL msg_id={msg_id[:40]}… err={exc}")
            text = f"(Transcription failed)"
        finally:
            self._pending.discard(msg_id)
        self._transcript_ready.emit(msg_id, text)

    def _run_transcriber(self, audio_path: str) -> str:
        # Fast path: socket already running (press-to-talk tray or auto-spawned server)
        text = self._try_socket(audio_path)
        if text is not None:
            return text
        # Auto-spawn a persistent --serve process on the host, then retry socket
        self._ensure_server()
        text = self._try_socket(audio_path)
        if text is not None:
            return text
        # Last resort: one-shot subprocess (slow — loads model each time)
        _write_log("WARNING: socket failed after spawn, falling back to one-shot subprocess")
        return self._run_host_subprocess(audio_path)

    _server_spawning = False

    def _ensure_server(self) -> None:
        """Auto-spawn a persistent --serve process on the host if not already running."""
        sock_path = SOCKET_PATH
        if sock_path.exists():
            return
        if VoiceTranscriptionService._server_spawning:
            import time
            for _ in range(120):
                if sock_path.exists():
                    return
                time.sleep(0.5)
            return
        VoiceTranscriptionService._server_spawning = True
        python = str(self._python_bin())
        script = str(self._script_path())
        cmd = [python, script, "--serve"]
        if _inside_flatpak():
            cmd = ["flatpak-spawn", "--host", *cmd]
        _write_log(f"auto-spawning server: {' '.join(cmd)}")
        try:
            subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            import time
            for _ in range(120):
                if sock_path.exists():
                    _write_log("server socket appeared")
                    return
                time.sleep(0.5)
            _write_log("WARNING: server socket did not appear after 60s")
        except Exception as exc:
            _write_log(f"failed to spawn server: {exc}")
        finally:
            VoiceTranscriptionService._server_spawning = False

    def _try_socket(self, audio_path: str) -> str | None:
        configured = self._config.get("socket")
        sock_path = Path(configured).expanduser() if configured else SOCKET_PATH
        if not sock_path.exists():
            return None
        payload = json.dumps({"path": audio_path}) + "\n"
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                client.settimeout(600)
                client.connect(str(sock_path))
                client.sendall(payload.encode("utf-8"))
                data = b""
                while b"\n" not in data:
                    chunk = client.recv(65536)
                    if not chunk:
                        break
                    data += chunk
                line = data.split(b"\n", 1)[0].decode("utf-8")
                response = json.loads(line)
                if response.get("error"):
                    raise RuntimeError(response["error"])
                _write_log(f"socket OK for {audio_path}")
                return str(response.get("text", "")).strip()
        except Exception as exc:
            _write_log(f"socket failed ({exc}), using subprocess")
            return None

    def _run_host_subprocess(self, audio_path: str) -> str:
        python = str(self._python_bin())
        script = str(self._script_path())
        cmd = [python, script, "--transcribe-file", audio_path]
        if _inside_flatpak():
            cmd = ["flatpak-spawn", "--host", *cmd]
        _write_log(f"subprocess: {' '.join(cmd[:6])}…")
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=600,
        )
        if result.returncode != 0:
            err = (result.stderr or result.stdout or "").strip()
            raise RuntimeError(err or f"exit code {result.returncode}")
        return result.stdout.strip()

    def _deliver(self, msg_id: str, text: str) -> None:
        page = getattr(self._webview, "whatsapp_page", None)
        if page is None:
            _write_log(f"deliver SKIP (no page) msg_id={msg_id[:40]}…")
            return
        _write_log(f"deliver msg_id={msg_id[:40]}… len={len(text)}")
        payload = json.dumps({"msgId": msg_id, "text": text})
        page.runJavaScript(f"window.__pttDeliverTranscript?.({payload});")
