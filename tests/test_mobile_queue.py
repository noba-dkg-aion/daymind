from pathlib import Path

from mobile.daymind.store.queue_store import ChunkQueue


def test_chunk_queue_enqueue_and_retry(tmp_path):
    queue = ChunkQueue(tmp_path / "queue.json")
    chunk_path = tmp_path / "chunk.flac"
    chunk_path.write_bytes(b"data")

    segments = [{"start_ms": 0, "end_ms": 1200, "start_utc": "2024-01-01T00:00:00Z", "end_utc": "2024-01-01T00:00:01.2Z"}]
    entry_id = queue.enqueue(
        str(chunk_path),
        session_start="2024-01-01T00:00:00Z",
        session_end="2024-01-01T00:00:06Z",
        speech_segments=segments,
    )
    assert len(queue) == 1
    entry = queue.peek()
    assert entry["id"] == entry_id
    assert entry["session_start"] == "2024-01-01T00:00:00Z"
    assert entry["speech_segments"] == segments

    queue.mark_failed(entry_id, "timeout")
    import time

    time.sleep(2)
    entry2 = queue.peek()
    assert entry2 is not None
    queue.mark_sent(entry_id)
    assert len(queue) == 0
