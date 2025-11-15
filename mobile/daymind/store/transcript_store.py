"""Append-only transcript archive for uploaded chunks."""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable


class TranscriptStore:
    """Persist chunk transcripts with subtitle-style timestamps."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._counter_path = self.path.with_suffix(self.path.suffix + ".idx")

    def append(
        self,
        *,
        chunk_id: str,
        text: str,
        session_start: str | None,
        session_end: str | None,
        speech_segments: Iterable[dict[str, Any]] | None,
    ) -> None:
        sentences = self._split_sentences(text)
        if not sentences:
            sentences = [text.strip() or "(no transcription)"]
        segments = list(speech_segments or [])
        if not segments:
            segments = [
                {
                    "start_utc": session_start,
                    "end_utc": session_end,
                    "start_ms": 0,
                    "end_ms": 0,
                }
            ]
        start_index = self._reserve_index(len(segments))
        lines: list[str] = []
        for offset, segment in enumerate(segments):
            entry_index = start_index + offset
            start_ts = self._segment_timestamp(
                segment,
                fallback_iso=session_start,
                fallback_offset_key="start_ms",
            )
            end_ts = self._segment_timestamp(
                segment,
                fallback_iso=session_start,
                fallback_offset_key="end_ms",
            )
            sentence = sentences[min(offset, len(sentences) - 1)].strip()
            if not sentence:
                sentence = sentences[-1].strip() or "(blank segment)"
            lines.extend(
                [
                    str(entry_index),
                    f"{start_ts} --> {end_ts}",
                    f"{chunk_id}: {sentence}",
                    "",
                ]
            )
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")

    def _reserve_index(self, count: int) -> int:
        current = 0
        if self._counter_path.exists():
            try:
                current = int(self._counter_path.read_text().strip() or "0")
            except ValueError:
                current = 0
        start = current + 1
        self._counter_path.write_text(str(current + count), encoding="utf-8")
        return start

    def _segment_timestamp(
        self,
        segment: dict[str, Any],
        *,
        fallback_iso: str | None,
        fallback_offset_key: str,
    ) -> str:
        iso_value = segment.get("start_utc") if fallback_offset_key == "start_ms" else segment.get("end_utc")
        if not iso_value and fallback_iso:
            offset_ms = segment.get(fallback_offset_key)
            if isinstance(offset_ms, (int, float)):
                iso_value = self._add_offset(fallback_iso, float(offset_ms))
        return self._format_timestamp(iso_value)

    @staticmethod
    def _add_offset(iso_value: str, offset_ms: float) -> str:
        try:
            base = TranscriptStore._parse_iso(iso_value)
        except ValueError:
            return iso_value
        delta = timedelta(milliseconds=offset_ms)
        return (base + delta).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _format_timestamp(value: str | None) -> str:
        if not value:
            return "00:00:00,000"
        try:
            dt = TranscriptStore._parse_iso(value)
        except ValueError:
            return "00:00:00,000"
        return dt.strftime("%H:%M:%S,%f")[:12]

    @staticmethod
    def _parse_iso(value: str) -> datetime:
        normalized = value.replace("Z", "+00:00")
        return datetime.fromisoformat(normalized).astimezone(timezone.utc)

    @staticmethod
    def _split_sentences(text: str) -> list[str]:
        chunks = [chunk.strip() for chunk in re.split(r"(?<=[.!?])\s+", text) if chunk.strip()]
        return chunks if chunks else ([text.strip()] if text.strip() else [])


__all__ = ["TranscriptStore"]
