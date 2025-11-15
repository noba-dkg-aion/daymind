"""Background worker that drains the chunk queue."""

from __future__ import annotations

import threading
import time
from pathlib import Path

from .logger import LogBuffer
from .network import ApiClient, ApiError
from ..store.queue_store import ChunkQueue
from ..store.transcript_store import TranscriptStore


class UploadWorker:
    def __init__(
        self,
        queue: ChunkQueue,
        client: ApiClient,
        logger: LogBuffer,
        transcripts: TranscriptStore,
    ) -> None:
        self.queue = queue
        self.client = client
        self.logger = logger
        self.transcripts = transcripts
        self._stop_event = threading.Event()
        self._wake_event = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._wake_event.set()
        if self._thread:
            self._thread.join(timeout=2)

    def wake(self) -> None:
        self._wake_event.set()

    def _run(self) -> None:
        while not self._stop_event.is_set():
            entry = self.queue.peek()
            if not entry:
                self._wake_event.wait(timeout=2)
                self._wake_event.clear()
                continue
            entry_id = entry["id"]
            try:
                self.logger.add(f"Uploading chunk {entry_id[:6]}...")
                metadata = {
                    "session_start": entry.get("session_start"),
                    "session_end": entry.get("session_end"),
                    "speech_segments": entry.get("speech_segments"),
                }
                response = self.client.upload_chunk(entry["path"], entry.get("lang", "auto"), metadata)
            except ApiError as exc:
                self.logger.add(f"Upload failed ({entry_id[:6]}): {exc}")
                self.queue.mark_failed(entry_id, str(exc))
                time.sleep(1)
            else:
                try:
                    Path(entry["path"]).unlink(missing_ok=True)
                except Exception:
                    pass
                self.queue.mark_sent(entry_id)
                self._persist_transcript(entry_id, entry, response)
                self.logger.add(f"Chunk {entry_id[:6]} sent")

    def _persist_transcript(self, entry_id: str, entry: dict, response: dict | None) -> None:
        if not response:
            return
        text = response.get("text", "").strip()
        if not text:
            return
        session_start = entry.get("session_start") or response.get("session_start")
        session_end = entry.get("session_end") or response.get("session_end")
        speech_segments = response.get("speech_segments") or entry.get("speech_segments")
        try:
            self.transcripts.append(
                chunk_id=entry_id,
                text=text,
                session_start=session_start,
                session_end=session_end,
                speech_segments=speech_segments,
            )
        except Exception as exc:  # pragma: no cover - best effort persistence
            self.logger.add(f"Transcript save failed ({entry_id[:6]}): {exc}")
