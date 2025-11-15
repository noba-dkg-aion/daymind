"""Dataclasses shared across audio helpers."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import List


@dataclass(slots=True)
class SpeechSegment:
    """Relative speech window (milliseconds offset within the chunk)."""

    start_ms: int
    end_ms: int


@dataclass(slots=True)
class RecordedChunk:
    """Metadata for a chunk captured by the local recorder."""

    path: str
    session_start: datetime
    session_end: datetime
    speech_segments: List[SpeechSegment]
