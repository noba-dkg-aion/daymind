"""Speech-aware segmenter with WebRTC VAD fallback detection."""

from __future__ import annotations

import numpy as np

from .types import SpeechSegment

try:  # Optional dependency; falls back to amplitude threshold if missing.
    import webrtcvad
except Exception:  # pragma: no cover - best effort import
    webrtcvad = None


class SpeechSegmenter:
    """Detect speech windows and trim silence using WebRTC VAD when available."""

    def __init__(
        self,
        sample_rate: int,
        *,
        vad_aggressiveness: int = 2,
        min_speech_ms: int = 250,
        min_gap_ms: int = 250,
        padding_ms: int = 150,
        amplitude_threshold: int = 1500,
        frame_ms: int = 30,
    ) -> None:
        self.sample_rate = sample_rate
        self.frame_ms = frame_ms
        self.min_speech_samples = self._ms_to_samples(min_speech_ms)
        self.min_gap_samples = self._ms_to_samples(min_gap_ms)
        self.padding_samples = self._ms_to_samples(padding_ms)
        self.amplitude_threshold = amplitude_threshold
        self._vad = None
        if webrtcvad:
            try:
                self._vad = webrtcvad.Vad(min(max(vad_aggressiveness, 0), 3))
            except Exception:
                self._vad = None

    def process(self, pcm: np.ndarray) -> tuple[np.ndarray, list[SpeechSegment]]:
        mono = self._ensure_mono(pcm)
        raw_segments = self._detect_segments(mono)
        prepared = self._prepare_segments(raw_segments, len(mono))
        if not prepared:
            return np.array([], dtype=np.int16), []
        trimmed = self._collect_audio(mono, [(pad_start, pad_end) for *_ignored, pad_start, pad_end in prepared])
        metadata = [
            SpeechSegment(
                start_ms=self._samples_to_ms(start),
                end_ms=self._samples_to_ms(end),
            )
            for start, end, *_ in prepared
        ]
        return trimmed, metadata

    def _detect_segments(self, samples: np.ndarray) -> list[tuple[int, int]]:
        if self._vad:
            return self._detect_with_webrtc(samples)
        return self._detect_with_threshold(samples)

    def _detect_with_webrtc(self, samples: np.ndarray) -> list[tuple[int, int]]:
        frame_len = self._ms_to_samples(self.frame_ms)
        if frame_len <= 0:
            return []
        segments: list[tuple[int, int]] = []
        start: int | None = None
        end = 0
        for offset in range(0, len(samples) - frame_len + 1, frame_len):
            frame = samples[offset : offset + frame_len]
            try:
                speech = self._vad.is_speech(frame.tobytes(), self.sample_rate)  # type: ignore[union-attr]
            except Exception:
                speech = False
            if speech:
                if start is None:
                    start = offset
                end = offset + frame_len
            else:
                if start is not None:
                    segments.append((start, end))
                    start = None
        if start is not None:
            segments.append((start, end))
        return segments

    def _detect_with_threshold(self, samples: np.ndarray) -> list[tuple[int, int]]:
        threshold = self.amplitude_threshold
        segments: list[tuple[int, int]] = []
        start: int | None = None
        silence = 0
        for idx, value in enumerate(samples):
            amplitude = abs(int(value))
            if amplitude >= threshold:
                if start is None:
                    start = idx
                silence = 0
            elif start is not None:
                silence += 1
                if silence >= self.min_gap_samples:
                    segments.append((start, idx))
                    start = None
                    silence = 0
        if start is not None:
            segments.append((start, len(samples)))
        return segments

    def _prepare_segments(
        self, segments: list[tuple[int, int]], total_samples: int
    ) -> list[tuple[int, int, int, int]]:
        if not segments:
            return []
        merged: list[tuple[int, int]] = []
        cur_start, cur_end = segments[0]
        for start, end in segments[1:]:
            if start - cur_end <= self.min_gap_samples:
                cur_end = max(cur_end, end)
            else:
                merged.append((cur_start, cur_end))
                cur_start, cur_end = start, end
        merged.append((cur_start, cur_end))

        prepared: list[tuple[int, int, int, int]] = []
        for start, end in merged:
            if end - start < self.min_speech_samples:
                continue
            pad_start = max(0, start - self.padding_samples)
            pad_end = min(total_samples, end + self.padding_samples)
            prepared.append((start, end, pad_start, pad_end))
        return prepared

    def _collect_audio(self, samples: np.ndarray, spans: list[tuple[int, int]]) -> np.ndarray:
        if not spans:
            return np.array([], dtype=np.int16)
        chunks = [samples[start:end] for start, end in spans if end > start]
        if not chunks:
            return np.array([], dtype=np.int16)
        return np.concatenate(chunks).astype(np.int16, copy=False)

    def _ensure_mono(self, pcm: np.ndarray) -> np.ndarray:
        data = np.asarray(pcm)
        if data.ndim == 1:
            return data.astype(np.int16, copy=False)
        return data[:, 0].astype(np.int16, copy=False)

    def _ms_to_samples(self, duration_ms: int) -> int:
        return max(1, int(self.sample_rate * (duration_ms / 1000.0)))

    def _samples_to_ms(self, sample_idx: int) -> int:
        return int(sample_idx * 1000 / self.sample_rate)
