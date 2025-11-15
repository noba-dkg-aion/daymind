"""Pydantic schemas for API contracts."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class TranscribeResponse(BaseModel):
    text: str
    lang: str = Field(default="auto")
    start: float = 0.0
    end: float = 0.0
    confidence: float | None = None
    session_id: int | None = None
    session_start: str | None = None
    session_end: str | None = None
    speech_segments: list[Dict[str, Any]] | None = None


class IngestRequest(BaseModel):
    text: str
    start: float = 0.0
    end: float = 0.0
    lang: str = "auto"
    meta: Dict[str, Any] = Field(default_factory=dict)


class IngestResponse(BaseModel):
    status: str = "ok"
    stored_at: float


class LedgerEntry(BaseModel):
    session_id: int | None = None
    input: str
    start: float | None = None
    end: float | None = None
    gpt_output: Any | None = None
    gap: float | None = None
    ts: float | None = None


class LedgerResponse(BaseModel):
    date: str
    count: int
    entries: list[LedgerEntry]


class SummaryResponse(BaseModel):
    date: str
    summary_md: str


class HealthResponse(BaseModel):
    ok: bool
    redis: str
    disk: str
    openai: str
    tls: str
    timestamp: datetime


class FinanceSummaryItem(BaseModel):
    date: str
    category: str
    total: float
    currency: str


class FinanceSummaryResponse(BaseModel):
    count: int
    items: list[FinanceSummaryItem]


class UsageResponse(BaseModel):
    owner: str
    created_at: float
    usage_count: int
    requests_today: int
    last_used: float | None = None


class SpeechWindow(BaseModel):
    start_utc: datetime = Field(alias="start_utc")
    end_utc: datetime = Field(alias="end_utc")


class ManifestChunk(BaseModel):
    chunk_id: str
    session_start: datetime
    session_end: datetime
    speech_segments: List[SpeechWindow] = Field(default_factory=list)


class ArchiveManifestPayload(BaseModel):
    archive_id: str
    generated_utc: datetime
    chunk_count: int
    chunks: List[ManifestChunk]


class BatchTranscribeResponse(BaseModel):
    status: str = "ok"
    archive_id: str
    processed: int
