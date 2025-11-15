package com.symbioza.daymind.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranscriptEntry(
    val id: String,
    val timestamp: Long,
    val summary: String,
    val chunkId: String,
    val srtPath: String
)

class TranscriptStore(context: Context) {
    private val baseDir = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val listFile = File(baseDir, "transcripts.jsonl")
    private val _entries = MutableStateFlow(readEntries())
    val entriesFlow: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    fun append(
        chunkId: String,
        text: String,
        sessionStart: String?,
        sessionEnd: String?,
        segments: List<Map<String, Any>>
    ) {
        if (text.isBlank()) return
        val entryId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val srtFile = File(baseDir, "$entryId.srt")
        srtFile.writeText(buildSrt(text, sessionStart, sessionEnd, segments), Charsets.UTF_8)
        val summary = text.lineSequence().firstOrNull()?.take(140) ?: "(empty)"
        val json = mapOf(
            "id" to entryId,
            "chunk_id" to chunkId,
            "ts" to timestamp,
            "summary" to summary,
            "srt_path" to srtFile.absolutePath
        )
        listFile.appendText("${serialize(json)}\n", Charsets.UTF_8)
        _entries.value = readEntries()
    }

    fun entries(): List<TranscriptEntry> = _entries.value

    private fun readEntries(): List<TranscriptEntry> {
        if (!listFile.exists()) return emptyList()
        return listFile.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                runCatching {
                    val data = deserialize(line)
                    TranscriptEntry(
                        id = data["id"] as String,
                        timestamp = (data["ts"] as Number).toLong(),
                        summary = data["summary"] as String,
                        chunkId = data["chunk_id"] as String,
                        srtPath = data["srt_path"] as String
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.timestamp }
    }

    private fun buildSrt(
        text: String,
        sessionStart: String?,
        sessionEnd: String?,
        segments: List<Map<String, Any>>
    ): String {
        val lines = mutableListOf<String>()
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss,SSS", Locale.US)
        val fallbackStart = sessionStart?.let { Instant.parse(it.replace("Z", "+00:00")) } ?: Instant.now()
        val fallbackEnd = sessionEnd?.let { Instant.parse(it.replace("Z", "+00:00")) } ?: fallbackStart
        val spans = if (segments.isEmpty()) {
            listOf(mapOf("start_utc" to fallbackStart.toString(), "end_utc" to fallbackEnd.toString()))
        } else segments
        spans.forEachIndexed { index, seg ->
            val start = seg["start_utc"]?.toString()?.let { Instant.parse(it.replace("Z", "+00:00")) } ?: fallbackStart
            val end = seg["end_utc"]?.toString()?.let { Instant.parse(it.replace("Z", "+00:00")) } ?: start
            lines.add((index + 1).toString())
            lines.add("${formatter.format(start.atOffset(ZoneOffset.UTC))} --> ${formatter.format(end.atOffset(ZoneOffset.UTC))}")
            lines.add(text)
            lines.add("")
        }
        return lines.joinToString("\n")
    }

    private fun serialize(data: Map<String, Any>): String {
        return buildString {
            append("{")
            append(data.entries.joinToString(",") { (k, v) ->
                val value = when (v) {
                    is Number, is Boolean -> v.toString()
                    else -> "\"${v}\""
                }
                "\"$k\":$value"
            })
            append("}")
        }
    }

    private fun deserialize(json: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val trimmed = json.trim().removePrefix("{").removeSuffix("}")
        if (trimmed.isBlank()) return result
        trimmed.split(",").forEach { pair ->
            val (rawKey, rawValue) = pair.split(":", limit = 2)
            val key = rawKey.trim().removePrefix("\"").removeSuffix("\"")
            val value = rawValue.trim().removePrefix("\"").removeSuffix("\"")
            result[key] = value
        }
        return result
    }
}
