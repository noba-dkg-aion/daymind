"""Lazy Whisper (faster-whisper) loader + mock fallback."""

from __future__ import annotations

import logging
import threading
from pathlib import Path
from typing import Iterable, Tuple

import numpy as np

try:  # pragma: no cover - optional heavy dependency
    from faster_whisper import WhisperModel  # type: ignore
except Exception:  # pragma: no cover
    WhisperModel = None  # type: ignore

from ..settings import APISettings

LOGGER = logging.getLogger("daymind.whisper")


class WhisperEngine:
    """Thin wrapper that loads Whisper on demand and falls back to mock mode."""

    def __init__(self, settings: APISettings) -> None:
        self.settings = settings
        self._lock = threading.Lock()
        self._model = None
        self._mock = settings.whisper_mock_transcriber or WhisperModel is None
        if self._mock:
            LOGGER.warning(
                "Whisper mock mode enabled (set WHISPER_USE_MOCK=0 and configure "
                "WHISPER_MODEL to enable real transcription)."
            )

    def _load_model(self) -> WhisperModel:
        if self._mock:
            raise RuntimeError("Mock mode does not load real Whisper models")
        if self._model is None:
            with self._lock:
                if self._model is None:
                    try:
                        self._model = WhisperModel(
                            self.settings.whisper_model,
                            device=self.settings.whisper_device,
                            compute_type=self.settings.whisper_compute_type,
                        )
                    except Exception as exc:  # pragma: no cover - hardware/env dep
                        LOGGER.error(
                            "Failed to load Whisper model '%s': %s",
                            self.settings.whisper_model,
                            exc,
                        )
                        raise
        return self._model

    def transcribe_path(
        self, path: Path, language: str | None = None
    ) -> Tuple[str, float, float, str, float | None]:
        if self._mock:
            text = f"[mock transcript for {path.name}]"
            duration = 0.0
            return text, 0.0, duration, language or "auto", None
        model = self._load_model()
        segments, info = model.transcribe(str(path), language=language, beam_size=5)
        return _summarize_segments(segments, info)

    def transcribe_audio(
        self, audio: np.ndarray, sample_rate: int, language: str | None = None
    ) -> Tuple[str, float, float, str, float | None]:
        if self._mock:
            duration = len(audio) / float(sample_rate)
            text = f"[mock transcript {len(audio)} samples]"
            return text, 0.0, duration, language or "auto", None
        model = self._load_model()
        segments, info = model.transcribe(
            audio=audio, language=language, beam_size=5, vad_filter=True
        )
        return _summarize_segments(segments, info)


def _summarize_segments(segments: Iterable, info) -> Tuple[str, float, float, str, float | None]:
    pieces = []
    start = None
    end = 0.0
    for segment in segments:
        pieces.append(segment.text.strip())
        if start is None:
            start = getattr(segment, "start", 0.0) or 0.0
        end = max(end, getattr(segment, "end", 0.0) or 0.0)
    text = " ".join(piece for piece in pieces if piece).strip()
    lang = getattr(info, "language", None) or "auto"
    confidence = getattr(info, "language_probability", None)
    if start is None:
        start = 0.0
    return text or "", float(start), float(end), lang, confidence
