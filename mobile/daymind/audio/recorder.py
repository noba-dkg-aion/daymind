"""Audio recording helper with chunked output."""

from __future__ import annotations

import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Optional

import numpy as np
import soundfile as sf

from ..config import CONFIG
from ..services.logger import LogBuffer
from .noise_filter import NoiseReducer
from .speech_segmenter import SpeechSegmenter
from .types import RecordedChunk


class AudioRecorder:
    def __init__(
        self,
        output_dir: Path,
        logger: LogBuffer,
        on_chunk: Callable[[RecordedChunk], None],
        *,
        level_callback: Callable[[float], None] | None = None,
        amplitude_threshold: int = 3500,
        vad_aggressiveness: int = 2,
        noise_gate: float = 0.12,
    ) -> None:
        self.output_dir = output_dir
        self.logger = logger
        self.on_chunk = on_chunk
        self.chunk_seconds = CONFIG.chunk_seconds
        self.sample_rate = CONFIG.sample_rate
        self.channels = CONFIG.channels
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._sd = self._try_import_sounddevice()
        self.segmenter = SpeechSegmenter(
            self.sample_rate,
            amplitude_threshold=amplitude_threshold,
            vad_aggressiveness=vad_aggressiveness,
        )
        self.noise_gate = max(0.0, min(float(noise_gate), 1.0))
        self.noise_reducer = NoiseReducer(self.sample_rate)
        self.level_callback = level_callback

    def _try_import_sounddevice(self):
        try:
            import sounddevice as sd  # type: ignore

            return sd
        except Exception:
            return None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self.logger.add("Recording started")
        self._stop.clear()
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)
        self.logger.add("Recording stopped")

    def _loop(self) -> None:
        while not self._stop.is_set():
            chunk = self._record_chunk()
            if chunk:
                self.on_chunk(chunk)
            time.sleep(0.1)

    def _record_chunk(self) -> Optional[RecordedChunk]:
        session_start = datetime.now(timezone.utc)
        frames = self.sample_rate * self.chunk_seconds
        pcm = self._capture_audio(frames)
        if pcm is None:
            return None
        pcm = self.noise_reducer.apply(pcm)
        if not self._passes_noise_gate(pcm):
            self.logger.add("Ambient noise below gate; chunk skipped")
            return None
        self._report_level(pcm)
        return self._finalize_chunk(pcm, session_start)

    def _capture_audio(self, frames: int) -> Optional[np.ndarray]:
        if self._sd:
            try:
                data = self._sd.rec(frames, samplerate=self.sample_rate, channels=self.channels, dtype="int16")
                self._sd.wait()
                return self._to_mono_array(np.array(data, dtype=np.int16))
            except Exception as exc:
                self.logger.add(f"sounddevice error: {exc}")
        import array

        buffer = array.array("h", [0] * frames)
        return np.array(buffer, dtype=np.int16)

    def _finalize_chunk(self, pcm: np.ndarray, session_start: datetime) -> Optional[RecordedChunk]:
        trimmed, segments = self.segmenter.process(pcm)
        if not segments or trimmed.size == 0:
            self.logger.add("No speech detected; chunk discarded")
            return None
        filename = f"chunk_{int(session_start.timestamp()*1000)}.flac"
        path = self.output_dir / filename
        self._write_flac(path, trimmed)
        duration = len(pcm) / self.sample_rate
        session_end = session_start + timedelta(seconds=duration)
        return RecordedChunk(
            path=str(path),
            session_start=session_start,
            session_end=session_end,
            speech_segments=segments,
        )

    def _report_level(self, pcm: np.ndarray) -> None:
        if not self.level_callback:
            return
        try:
            level = float(np.max(np.abs(pcm))) / 32768.0
        except ValueError:
            level = 0.0
        level = max(0.0, min(1.0, level))
        self.level_callback(level)
        return level

    def _passes_noise_gate(self, pcm: np.ndarray) -> bool:
        peak = float(np.max(np.abs(pcm))) / 32768.0 if pcm.size else 0.0
        return peak >= self.noise_gate

    def _write_flac(self, path: Path, samples: np.ndarray) -> None:
        sf.write(str(path), samples.astype(np.int16, copy=False), self.sample_rate, format="FLAC", subtype="PCM_16")

    def _to_mono_array(self, data: np.ndarray) -> np.ndarray:
        if data.ndim == 1:
            return data
        return data[:, 0]

    def set_vad_threshold(self, value: int) -> None:
        self.segmenter.set_amplitude_threshold(value)

    def set_vad_aggressiveness(self, value: int) -> None:
        self.segmenter.set_vad_aggressiveness(value)

    def set_noise_gate(self, value: float) -> None:
        self.noise_gate = max(0.0, min(float(value), 1.0))
