"""Helpers for ingesting transcripts and audio files."""

from __future__ import annotations

import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional

import soundfile as sf
from fastapi import UploadFile

from src.stt_core.buffer_store import BufferStore
from src.stt_core.redis_io import RedisPublisher

from ..schemas import ArchiveManifestPayload, ManifestChunk
from ..settings import APISettings
from .whisper_engine import WhisperEngine


class TranscriptService:
    """Store audio uploads and convert them into transcript records."""

    def __init__(self, settings: APISettings) -> None:
        self.settings = settings
        self.buffer = BufferStore(settings.transcript_path)
        self.whisper = WhisperEngine(settings)
        self._redis: Optional[RedisPublisher] = None
        if settings.redis_url:
            self._redis = RedisPublisher(settings.redis_url, settings.redis_stream)

    async def save_audio(
        self, file: UploadFile, lang: str | None = None
    ) -> Dict[str, Any]:
        """Handle a single wav/flac upload."""

        lang = lang or "auto"
        tmp_dir = Path(self.settings.data_dir) / "uploads"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        tmp_path = tmp_dir / f"{int(time.time() * 1000)}_{file.filename}"
        tmp_path.write_bytes(await file.read())

        text, start_offset, end_offset, final_lang, confidence = (
            self.whisper.transcribe_path(tmp_path, language=lang)
        )
        now = time.time()
        entry = {
            "text": text,
            "lang": final_lang,
            "start": start_offset,
            "end": end_offset,
            "confidence": confidence,
            "session_id": int(now),
            "source": str(tmp_path),
        }
        self.buffer.append(entry)
        await self._publish(entry)
        return entry

    async def process_archive(
        self, archive_file: UploadFile, manifest_payload: str
    ) -> Dict[str, Any]:
        """Process a FLAC archive + manifest produced by the Kotlin client."""

        manifest = ArchiveManifestPayload.model_validate_json(manifest_payload)
        archive_dir = Path(self.settings.data_dir) / "archives" / manifest.archive_id
        archive_dir.mkdir(parents=True, exist_ok=True)
        archive_path = archive_dir / archive_file.filename
        archive_path.write_bytes(await archive_file.read())
        (archive_dir / "manifest.json").write_text(manifest_payload, encoding="utf-8")

        audio, sample_rate = sf.read(str(archive_path), dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        pointer = 0
        total_samples = len(audio)
        processed = 0

        for idx, chunk in enumerate(manifest.chunks):
            samples_needed = self._samples_for_chunk(chunk, sample_rate)
            end_pointer = pointer + samples_needed
            if idx == len(manifest.chunks) - 1 or end_pointer > total_samples:
                end_pointer = total_samples
            chunk_audio = audio[pointer:end_pointer]
            pointer = end_pointer
            if chunk_audio.size == 0:
                continue
            text, _, _, final_lang, confidence = self.whisper.transcribe_audio(
                chunk_audio, sample_rate, language=None
            )
            entry = {
                "text": text,
                "lang": final_lang,
                "start": _to_epoch(chunk.session_start),
                "end": _to_epoch(chunk.session_end),
                "confidence": confidence,
                "session_id": chunk.chunk_id,
                "archive_id": manifest.archive_id,
                "speech_segments": [
                    {
                        "start": _to_epoch(window.start_utc),
                        "end": _to_epoch(window.end_utc),
                    }
                    for window in chunk.speech_segments
                ],
                "source": str(archive_path),
            }
            self.buffer.append(entry)
            await self._publish(entry)
            processed += 1

        return {"status": "ok", "archive_id": manifest.archive_id, "processed": processed}

    async def ingest_text(self, payload: Dict[str, Any]) -> float:
        payload.setdefault("ts", time.time())
        self.buffer.append(payload)
        await self._publish(payload)
        return payload["ts"]

    def _samples_for_chunk(self, chunk: ManifestChunk, sample_rate: int) -> int:
        duration = (chunk.session_end - chunk.session_start).total_seconds()
        return max(1, int(round(duration * sample_rate)))

    async def _publish(self, payload: Dict[str, Any]) -> None:
        if not self._redis:
            return
        try:
            await self._redis.publish(payload)
        except Exception:
            return


def _to_epoch(value: datetime) -> float:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.timestamp()
