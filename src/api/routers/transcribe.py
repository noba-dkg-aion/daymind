"""Transcription endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, UploadFile

from ..deps.auth import get_api_key
from ..schemas import BatchTranscribeResponse, TranscribeResponse
from ..services.transcript_service import TranscriptService
from ..settings import APISettings, get_settings

router = APIRouter(prefix="/v1", tags=["transcribe"])


def get_service(settings: APISettings = Depends(get_settings)) -> TranscriptService:
    return TranscriptService(settings)


@router.post("/transcribe", response_model=TranscribeResponse)
async def transcribe_audio(
    file: UploadFile = File(...),
    lang: str | None = Form(None),
    session_start: str | None = Form(None),
    session_end: str | None = Form(None),
    speech_segments: str | None = Form(None),
    _: str = Depends(get_api_key),
    service: TranscriptService = Depends(get_service),
):
    record = await service.save_audio(file, lang, session_start, session_end, speech_segments)
    return TranscribeResponse(
        text=record["text"],
        lang=record.get("lang", "auto"),
        start=record.get("start", 0.0),
        end=record.get("end", 0.0),
        confidence=record.get("confidence"),
        session_id=record.get("session_id"),
        session_start=record.get("session_start"),
        session_end=record.get("session_end"),
        speech_segments=record.get("speech_segments"),
        chunk_id=record.get("chunk_id"),
    )


@router.post("/transcribe/batch", response_model=BatchTranscribeResponse)
async def transcribe_archive(
    archive: UploadFile = File(...),
    manifest: str = Form(...),
    _: str = Depends(get_api_key),
    service: TranscriptService = Depends(get_service),
):
    result = await service.process_archive(archive, manifest)
    return BatchTranscribeResponse(**result)
