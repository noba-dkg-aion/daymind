package com.symbioza.daymind.data

import java.time.Instant

data class SpeechSegmentJson(
    val startMs: Long,
    val endMs: Long
)

data class ChunkMetadata(
    val id: String,
    val filePath: String,
    val sessionStartUtc: String,
    val createdAt: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val uploaded: Boolean = false,
    val speechSegments: List<SpeechSegmentJson>
) {
    val sessionEndUtc: String
        get() = Instant.parse(sessionStartUtc).plusMillis(durationMs).toString()
}

data class ArchiveManifest(
    val archiveId: String,
    val generatedUtc: String,
    val chunkCount: Int,
    val chunks: List<ArchiveManifestChunk>
)

data class ArchiveManifestChunk(
    val chunkId: String,
    val sessionStartUtc: String,
    val sessionEndUtc: String,
    val speechSegmentsUtc: List<SpeechWindowUtc>
)

data class SpeechWindowUtc(
    val startUtc: String,
    val endUtc: String
)
