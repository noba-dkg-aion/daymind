from pathlib import Path

from mobile.daymind.store.transcript_store import TranscriptStore


def test_transcript_store_appends_segments(tmp_path):
    srt_path = tmp_path / "transcripts" / "daymind.srt"
    store = TranscriptStore(srt_path)
    segments = [
        {
            "start_utc": "2024-01-01T00:00:00Z",
            "end_utc": "2024-01-01T00:00:02Z",
            "start_ms": 0,
            "end_ms": 2000,
        },
        {
            "start_utc": "2024-01-01T00:00:02Z",
            "end_utc": "2024-01-01T00:00:04Z",
            "start_ms": 2000,
            "end_ms": 4000,
        },
    ]
    store.append(
        chunk_id="abc123",
        text="Hello world. Second sentence.",
        session_start="2024-01-01T00:00:00Z",
        session_end="2024-01-01T00:00:06Z",
        speech_segments=segments,
    )
    content = srt_path.read_text(encoding="utf-8")
    assert "abc123" in content
    assert "Hello world" in content
    assert "Second sentence" in content
    assert "00:00:00,000 --> 00:00:02,000" in content
