import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
import numpy as np
import soundfile as sf

from src.api.deps.auth import reset_auth_service_cache


@pytest.fixture()
def api_client(tmp_path, monkeypatch):
    transcripts = tmp_path / "transcripts.jsonl"
    ledger = tmp_path / "ledger.jsonl"
    summary_dir = tmp_path

    from src.api.settings import APISettings, get_settings
    from src.api.app import create_app

    get_settings.cache_clear()  # type: ignore
    reset_auth_service_cache()
    settings = APISettings(
        api_keys=["test-key"],
        api_key_store_path=str(tmp_path / "keys.json"),
        transcript_path=str(transcripts),
        ledger_path=str(ledger),
        summary_dir=str(summary_dir),
        data_dir=str(tmp_path),
        api_rate_limit_per_minute=120,
        ip_rate_limit_per_minute=0,
        whisper_mock_transcriber=True,
    )

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings

    client = TestClient(app)
    return client, transcripts, ledger, summary_dir


def _auth_headers():
    return {"X-API-Key": "test-key"}


def test_auth_required(api_client):
    client, *_ = api_client
    resp = client.get("/healthz")
    assert resp.status_code == 401


def test_health_ok(api_client):
    client, *_ = api_client
    resp = client.get("/healthz", headers=_auth_headers())
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["openai"] == "skip"
    assert "tls" in body


def test_ingest_transcript_appends(api_client):
    client, transcripts, *_ = api_client
    payload = {"text": "hello", "start": 0.0, "end": 1.0}
    resp = client.post("/v1/ingest-transcript", json=payload, headers=_auth_headers())
    assert resp.status_code == 200
    assert transcripts.exists()
    line = transcripts.read_text().strip()
    data = json.loads(line)
    assert data["text"] == "hello"


def test_ledger_endpoint(api_client):
    client, _, ledger, _ = api_client
    ts = datetime(2024, 11, 1).timestamp()
    entries = [
        {"input": "a", "start": ts, "session_id": 1},
        {"input": "b", "start": ts + 5, "session_id": 1},
    ]
    with open(ledger, "w", encoding="utf-8") as fh:
        for e in entries:
            fh.write(json.dumps(e) + "\n")
    resp = client.get("/v1/ledger?date=2024-11-01", headers=_auth_headers())
    assert resp.status_code == 200
    body = resp.json()
    assert body["count"] == 2


def test_summary_returns_file(api_client):
    client, _, _, summary_dir = api_client
    target = summary_dir / "summary_2024-11-01.md"
    target.write_text("## summary", encoding="utf-8")
    resp = client.get("/v1/summary?date=2024-11-01", headers=_auth_headers())
    assert resp.status_code == 200
    assert "summary" in resp.json()["summary_md"]


def test_transcribe_endpoint(api_client):
    client, transcripts, *_ = api_client
    audio_path = Path("tests/assets/sample_cs.wav")
    resp = client.post(
        "/v1/transcribe",
        headers=_auth_headers(),
        files={"file": (audio_path.name, audio_path.read_bytes(), "audio/wav")},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "text" in data
    assert "session_id" in data
    assert data.get("speech_segments") is None or isinstance(data["speech_segments"], list)
    assert transcripts.exists()


def test_transcribe_with_metadata(api_client):
    client, transcripts, *_ = api_client
    audio_path = Path("tests/assets/sample_cs.wav")
    segments = [
        {
            "start_ms": 0,
            "end_ms": 800,
            "start_utc": "2024-01-01T00:00:00Z",
            "end_utc": "2024-01-01T00:00:00.8Z",
        }
    ]
    resp = client.post(
        "/v1/transcribe",
        headers=_auth_headers(),
        files={"file": (audio_path.name, audio_path.read_bytes(), "audio/wav")},
        data={
            "session_start": "2024-01-01T00:00:00Z",
            "session_end": "2024-01-01T00:00:06Z",
            "speech_segments": json.dumps(segments),
        },
    )
    assert resp.status_code == 200
    resp_data = resp.json()
    assert resp_data["session_start"] == "2024-01-01T00:00:00Z"
    assert resp_data["speech_segments"][0]["start_utc"] == "2024-01-01T00:00:00Z"
    assert transcripts.exists()
    line = [line for line in transcripts.read_text().splitlines() if line][-1]
    data = json.loads(line)
    assert data["session_start"] == "2024-01-01T00:00:00Z"
    assert data["speech_segments"][0]["start_utc"] == "2024-01-01T00:00:00Z"


def test_batch_transcribe_endpoint(api_client, tmp_path):
    client, transcripts, *_ = api_client
    audio_path = Path("tests/assets/sample_cs.wav")
    audio, sr = sf.read(str(audio_path))
    chunk_len = sr // 4
    first = audio[:chunk_len]
    second = audio[chunk_len : chunk_len * 2]
    combined = np.concatenate([first, second])
    archive_path = tmp_path / "archive.flac"
    sf.write(str(archive_path), combined, sr, format="FLAC")

    start = datetime.now(timezone.utc).replace(microsecond=0)
    first_duration = len(first) / sr
    second_duration = len(second) / sr
    def _iso(dt: datetime) -> str:
        return dt.isoformat().replace("+00:00", "Z")

    manifest = {
        "archive_id": "test-archive",
        "generated_utc": _iso(start),
        "chunk_count": 2,
        "chunks": [
            {
                "chunk_id": "chunk-1",
                "session_start": _iso(start),
                "session_end": _iso(start + timedelta(seconds=first_duration)),
                "speech_segments": [
                    {
                        "start_utc": _iso(start),
                        "end_utc": _iso(start + timedelta(seconds=first_duration)),
                    }
                ],
            },
            {
                "chunk_id": "chunk-2",
                "session_start": _iso(start + timedelta(seconds=first_duration)),
                "session_end": _iso(
                    start + timedelta(seconds=first_duration + second_duration)
                ),
                "speech_segments": [
                    {
                        "start_utc": _iso(start + timedelta(seconds=first_duration)),
                        "end_utc": _iso(
                            start + timedelta(seconds=first_duration + second_duration)
                        ),
                    }
                ],
            },
        ],
    }

    files = {
        "archive": ("archive.flac", archive_path.read_bytes(), "audio/flac"),
    }
    data = {"manifest": json.dumps(manifest)}
    resp = client.post(
        "/v1/transcribe/batch", headers=_auth_headers(), files=files, data=data
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["processed"] == 2
    assert len(body["entries"]) == 2
    assert body["entries"][0]["text"]
    assert transcripts.exists()
    lines = [line for line in transcripts.read_text().strip().splitlines() if line]
    assert len(lines) >= 2


def test_metrics_secured(api_client):
    client, *_ = api_client
    resp = client.get("/metrics", headers=_auth_headers())
    assert resp.status_code == 200
    assert "api_requests_total" in resp.text


def test_usage_endpoint(api_client):
    client, *_ = api_client
    resp = client.get("/v1/usage", headers=_auth_headers())
    assert resp.status_code == 200
    body = resp.json()
    assert body["usage_count"] >= 1


def test_welcome_public(api_client):
    client, *_ = api_client
    resp = client.get("/welcome")
    assert resp.status_code == 200
    assert "docs" in resp.json()


def test_rate_limit_enforced(tmp_path):
    transcripts = tmp_path / "transcripts.jsonl"
    ledger = tmp_path / "ledger.jsonl"
    summary_dir = tmp_path

    from src.api.settings import APISettings, get_settings
    from src.api.app import create_app

    get_settings.cache_clear()  # type: ignore
    reset_auth_service_cache()
    settings = APISettings(
        api_keys=["test-key"],
        api_key_store_path=str(tmp_path / "keys.json"),
        transcript_path=str(transcripts),
        ledger_path=str(ledger),
        summary_dir=str(summary_dir),
        data_dir=str(tmp_path),
        api_rate_limit_per_minute=1,
        ip_rate_limit_per_minute=0,
    )

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    client = TestClient(app)
    headers = {"X-API-Key": "test-key"}

    assert client.get("/healthz", headers=headers).status_code == 200
    resp = client.get("/healthz", headers=headers)
    assert resp.status_code == 429
