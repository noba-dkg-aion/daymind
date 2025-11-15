"""Helpers for ingesting transcripts and audio files."""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional

import numpy as np
import soundfile as sf
from fastapi import UploadFile
from openai import AsyncOpenAI

from src.stt_core.buffer_store import BufferStore
from src.stt_core.redis_io import RedisPublisher

from ..metrics import ARCHIVE_SYNC_COUNTER, ARCHIVE_SYNC_DURATION
from ..schemas import ArchiveManifestPayload, ManifestChunk
from ..settings import APISettings
from .whisper_engine import WhisperEngine


class TranscriptService:
    """Store audio uploads and convert them into transcript records."""

    def __init__(self, settings: APISettings) -> None:
        self.settings = settings
        self.buffer = BufferStore(settings.transcript_path)
        self.whisper = WhisperEngine(settings)
        self.settings = settings
        self._openai_client: Optional[AsyncOpenAI] = None
        if settings.whisper_use_openai:
            if not settings.openai_api_key:
                raise RuntimeError("WHISPER_USE_OPENAI=1 but OPENAI_API_KEY is missing")
            self._openai_client = AsyncOpenAI(api_key=settings.openai_api_key)
        self._redis: Optional[RedisPublisher] = None
        if settings.redis_url:
            self._redis = RedisPublisher(settings.redis_url, settings.redis_stream)

    async def save_audio(
        self,
        file: UploadFile,
        lang: str | None = None,
        session_start: str | None = None,
        session_end: str | None = None,
        speech_segments_payload: str | None = None,
    ) -> Dict[str, Any]:
        """Handle a single wav/flac upload."""

        lang = lang or "auto"
        tmp_dir = Path(self.settings.data_dir) / "uploads"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        tmp_path = tmp_dir / f"{int(time.time() * 1000)}_{file.filename}"
        tmp_path.write_bytes(await file.read())

        text, start_offset, end_offset, final_lang, confidence = await self._transcribe_file(tmp_path, lang)
        now = time.time()
        entry = {
            "text": text,
            "lang": final_lang,
            "start": start_offset,
            "end": end_offset,
            "confidence": confidence,
            "session_id": int(now),
            "source": str(tmp_path),
            "chunk_id": tmp_path.stem,
        }
        if session_start:
            entry["session_start"] = session_start
        if session_end:
            entry["session_end"] = session_end
        if speech_segments_payload:
            entry["speech_segments"] = self._parse_speech_segments(speech_segments_payload)
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

        start_time = time.perf_counter()
        entries_out: list[Dict[str, Any]] = []
        try:
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
                text, _, _, final_lang, confidence = await self._transcribe_array(chunk_audio, sample_rate)
                session_label = idx + 1
                entry = {
                    "text": text,
                    "lang": final_lang,
                    "start": _to_epoch(chunk.session_start),
                    "end": _to_epoch(chunk.session_end),
                    "confidence": confidence,
                    "session_id": session_label,
                    "archive_id": manifest.archive_id,
                    "speech_segments": [
                        {
                            "start_utc": window.start_utc.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                            "end_utc": window.end_utc.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                        }
                        for window in chunk.speech_segments
                    ],
                    "source": str(archive_path),
                    "session_start": chunk.session_start.isoformat().replace("+00:00", "Z"),
                    "session_end": chunk.session_end.isoformat().replace("+00:00", "Z"),
                    "chunk_id": chunk.chunk_id,
                }
                self.buffer.append(entry)
                await self._publish(entry)
                entries_out.append(entry)
                processed += 1

            ARCHIVE_SYNC_COUNTER.labels(status="success").inc()
            ARCHIVE_SYNC_DURATION.observe(time.perf_counter() - start_time)
            return {
                "status": "ok",
                "archive_id": manifest.archive_id,
                "processed": processed,
                "entries": entries_out,
            }
        except Exception as exc:
            ARCHIVE_SYNC_COUNTER.labels(status="error").inc()
            ARCHIVE_SYNC_DURATION.observe(time.perf_counter() - start_time)
            raise

    async def ingest_text(self, payload: Dict[str, Any]) -> float:
        payload.setdefault("ts", time.time())
        self.buffer.append(payload)
        await self._publish(payload)
        return payload["ts"]

    def _samples_for_chunk(self, chunk: ManifestChunk, sample_rate: int) -> int:
        duration = (chunk.session_end - chunk.session_start).total_seconds()
        return max(1, int(round(duration * sample_rate)))

    def _parse_speech_segments(self, payload: str) -> list[Dict[str, Any]]:
        try:
            data = json.loads(payload)
        except json.JSONDecodeError:
            return []
        if isinstance(data, dict):
            data = [data]
        if not isinstance(data, list):
            return []
        segments: list[Dict[str, Any]] = []
        for item in data:
            if isinstance(item, dict):
                segments.append(item)
        return segments

    async def _publish(self, payload: Dict[str, Any]) -> None:
        if not self._redis:
            return
        try:
            await self._redis.publish(payload)
        except Exception:
            return

    async def _transcribe_file(self, path: Path, language: str | None) -> tuple[str, float, float, str, float | None]:
        if self.settings.whisper_use_openai:
            assert self._openai_client
            with path.open("rb") as handle:
                transcript = await self._openai_client.audio.transcriptions.create(
                    model=self.settings.openai_whisper_model,
                    file=("chunk.wav", handle, "audio/wav"),
                    response_format="verbose_json",
                )
            text = transcript.text or ""
            segments = transcript.segments or []
            start = segments[0].get("start", 0.0) if segments else 0.0
            end = segments[-1].get("end", 0.0) if segments else 0.0
            lang = transcript.language or language or "auto"
            return text, float(start), float(end), lang, None
        return self.whisper.transcribe_path(path, language=language)

    async def _transcribe_array(self, audio: np.ndarray, sample_rate: int) -> tuple[str, float, float, str, float | None]:
        if self.settings.whisper_use_openai:
            tmp = Path(self.settings.data_dir) / "tmp_array.wav"
            tmp.parent.mkdir(parents=True, exist_ok=True)
            sf.write(str(tmp), audio, sample_rate)
            result = await self._transcribe_file(tmp, None)
            tmp.unlink(missing_ok=True)
            return result
        return self.whisper.transcribe_audio(audio, sample_rate, language=None)


def _to_epoch(value: datetime) -> float:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.timestamp()
