import math

import numpy as np
import pytest

from mobile.daymind.audio import speech_segmenter as seg_mod


def _sine_wave(duration_s: float, sample_rate: int, amplitude: int = 12000) -> np.ndarray:
    length = int(duration_s * sample_rate)
    t = np.arange(length)
    waveform = np.sin(2 * math.pi * 220 * t / sample_rate)
    return (waveform * amplitude).astype(np.int16)


def test_segmenter_detects_speech_without_webrtc(monkeypatch):
    monkeypatch.setattr(seg_mod, "webrtcvad", None)
    sample_rate = 16_000
    silence = np.zeros(sample_rate // 2, dtype=np.int16)
    speech = _sine_wave(0.4, sample_rate)
    audio = np.concatenate([silence, speech, silence])

    segmenter = seg_mod.SpeechSegmenter(
        sample_rate=sample_rate,
        vad_aggressiveness=3,
        min_speech_ms=100,
        min_gap_ms=100,
        padding_ms=50,
    )

    trimmed, segments = segmenter.process(audio)
    assert trimmed.size > 0
    assert len(segments) == 1
    # Speech starts roughly halfway through the buffer; allow ±150 ms tolerance.
    assert 350 <= segments[0].start_ms <= 650
    assert segments[0].end_ms > segments[0].start_ms


def test_segmenter_uses_webrtc_when_available(monkeypatch):
    registry: dict[str, int | None] = {"count": 0, "level": None}

    class DummyVad:
        def __init__(self, data: dict[str, int | None]) -> None:
            self.data = data

        def is_speech(self, frame: bytes, sample_rate: int) -> bool:  # noqa: ARG002
            self.data["count"] = int(self.data["count"] or 0) + 1
            return True

    class DummyWebRTC:
        def __init__(self, data: dict[str, int | None]) -> None:
            self.data = data

        def Vad(self, level: int) -> DummyVad:  # noqa: N802
            self.data["level"] = level
            return DummyVad(self.data)

    monkeypatch.setattr(seg_mod, "webrtcvad", DummyWebRTC(registry))

    segmenter = seg_mod.SpeechSegmenter(sample_rate=8000, vad_aggressiveness=2)
    audio = np.ones(8000, dtype=np.int16)
    trimmed, segments = segmenter.process(audio)

    assert registry["count"] and registry["count"] > 0
    assert registry["level"] == 2
    assert trimmed.size > 0
    assert len(segments) == 1
