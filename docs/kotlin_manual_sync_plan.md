# Kotlin Audio Vault & Manual Sync Plan

> ✅ Implemented in the Kotlin client: manual **Sync now** button, FLAC archive builder, shareable exports, and `/v1/transcribe/batch` manifest flow. Remaining backend work: finalize archive ingestion + GPT alignment.

## Goals
1. **Persistent vault** – keep every trimmed chunk on-device until the operator explicitly syncs (no auto-deletion after upload).
2. **Aggregated audio** – provide a command that concatenates per-chunk audio into a single compressed artifact (e.g., FLAC) for archival/playback elsewhere.
3. **Manual synchronization** – uploads only happen when the operator taps **Sync now** (or selects a cadence such as hourly/daily). Chunk metadata (speech windows, session timestamps) travels with the compressed archive.
4. **Backend alignment** – `/v1/transcribe` must accept a compressed bundle + metadata describing the constituent speech windows so GPT can recover UTC offsets.

## Proposed Flow
### 1. Recording
- Continue capturing 6 s PCM chunks + silence trimming (`SilenceTrimmer`).
- Store WAV + `.segments.json` in `cacheDir/chunks` (rename to `cacheDir/vault/`).
- Maintain a SQLite/Room table referencing chunk path, start timestamp (`Instant`), duration, hash, upload state.

### 2. Vault Management
- ViewModel exposes:
  - `totalChunks`, `totalDuration`, `lastSyncedAt`.
  - List of pending chunks for future UI screens (optional now).
- Add **Build Archive** action:
  1. Concatenate PCM data in chronological order into a single WAV.
  2. Compress WAV to FLAC (preferred) using `androidx.media3.extractor.FlacFrameWriter` or the NDK `libFLAC`.
  3. Produce sidecar JSON:
     ```json
     {
       "session_start_utc": "...",
       "session_end_utc": "...",
       "chunks": [
         {
           "file": "chunk_123.wav",
           "start_utc": "...",
           "end_utc": "...",
           "speech_segments": [{"start_ms": 0, "end_ms": 1400}]
         }
       ]
     }
     ```
- Store the archive under `cacheDir/archives/archive_<date>.flac` + JSON manifest.

### 3. Manual Synchronization UI
- Replace auto enqueue with sticky state:
  - Each trimmed chunk has `upload_state = PENDING`.
  - Tapping **Sync now** builds/refreshes the archive and enqueues a `SyncArchiveWorker`.
  - Worker uploads `archive.flac` + manifest JSON to new backend endpoint (`/v1/transcribe/batch`).
- Provide scheduling options (`Off`, `Hourly`, `Daily`) stored in `ConfigRepository`; when enabled, `WorkManager` schedules a periodic sync. *(Manual button done; scheduling TBD.)*

### 4. Backend Changes (overview)
- Add `/v1/transcribe/batch`:
  - Accepts multipart: `archive` (FLAC), `manifest_json`.
  - Backend extracts per-chunk metadata, decodes FLAC -> PCM, splits by provided offsets, and reuses existing transcription pipeline.
- Accept `speech_segments` with either relative ms or absolute UTC (recommend storing `start_utc`/`end_utc` in manifest so GPT knows when speech happened even after concatenation).
- Store raw FLAC + manifest for download (optional).

### 5. Compression Considerations
- **FLAC**: lossless, better compression than WAV, no licensing issues. Media3 encoder is easiest; fallback to FFmpeg/Opus if space is critical.
- **Opus**: better ratio but lossy; requires JNI/FFmpeg.
- For MVP, start with FLAC; ensure decompress/concat occurs server-side.

### 6. Playback
- Keep per-chunk playback button (current feature).
- “Share archive” action now shares the FLAC file using `Intent.ACTION_SEND`, so the operator can open it in another app.

### 7. Testing
- Unit-test manifest builder + archive composer.
- Instrumented tests for manual sync button: create few mock chunks, tap sync, ensure WorkManager enqueues once, metadata matches chunk count.
- Backend integration test verifying `/v1/transcribe/batch` splits FLAC per manifest and retains UTC offsets.

## Next Steps
1. Optional: migrate manifest JSON to Room-backed storage for easier queries/limits.
2. Add scheduled sync cadence (hourly/daily) plus UI toggle.
3. Backend: finish `/v1/transcribe/batch` ingestion, persist FLAC + manifest, ensure GPT pipeline respects UTC speech windows.

This plan keeps all audio on-device until the operator approves, produces a single compressed file for external playback, and ensures GPT receives accurate speaking timelines even after aggregation.
