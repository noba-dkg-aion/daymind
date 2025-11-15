import json
from pathlib import Path

import pytest

from src.api.services.transcript_service import TranscriptService
from src.api.settings import APISettings


def _make_service(tmp_path: Path) -> TranscriptService:
    settings = APISettings(
        api_keys=["x"],
        api_key_store_path=str(tmp_path / "keys.json"),
        transcript_path=str(tmp_path / "transcripts.jsonl"),
        ledger_path=str(tmp_path / "ledger.jsonl"),
        summary_dir=str(tmp_path),
        data_dir=str(tmp_path),
        redis_url=None,
    )
    return TranscriptService(settings)


def test_parse_speech_segments_handles_list(tmp_path):
    service = _make_service(tmp_path)
    payload = json.dumps(
        [
            {"start_ms": 0, "end_ms": 1200, "start_utc": "2024-01-01T00:00:00Z"},
            {"start_ms": 1500, "end_ms": 3000, "start_utc": "2024-01-01T00:00:01.5Z"},
        ]
    )
    segments = service._parse_speech_segments(payload)
    assert len(segments) == 2
    assert segments[0]["end_ms"] == 1200
    assert segments[1]["start_ms"] == 1500


def test_parse_speech_segments_accepts_single_object(tmp_path):
    service = _make_service(tmp_path)
    payload = json.dumps({"start_ms": 0, "end_ms": 500})
    segments = service._parse_speech_segments(payload)
    assert len(segments) == 1
    assert segments[0]["end_ms"] == 500


def test_parse_speech_segments_gracefully_handles_invalid(tmp_path):
    service = _make_service(tmp_path)
    assert service._parse_speech_segments("not-json") == []
    assert service._parse_speech_segments("123") == []
    assert service._parse_speech_segments(json.dumps([1, 2, 3])) == []
