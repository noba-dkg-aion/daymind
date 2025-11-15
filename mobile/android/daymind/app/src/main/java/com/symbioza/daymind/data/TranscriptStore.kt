package com.symbioza.daymind.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

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

    fun append(chunkId: String, text: String, segments: List<Map<String, Any>>) {
        if (text.isBlank()) return
        val entryId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val srtFile = File(baseDir, "$entryId.srt")
        srtFile.writeText(buildSrt(text, segments), Charsets.UTF_8)
        val summary = text.lineSequence().firstOrNull()?.take(140) ?: "(empty)"
        val json = mapOf(
            "id" to entryId,
            "chunk_id" to chunkId,
            "ts" to timestamp,
            "summary" to summary,
            "srt_path" to srtFile.absolutePath
        )
        listFile.appendText("${serialize(json)}\n", Charsets.UTF_8)
    }

    fun entries(): List<TranscriptEntry> {
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

    private fun buildSrt(text: String, segments: List<Map<String, Any>>): String {
        val lines = mutableListOf<String>()
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss,SSS", Locale.US)
        segments.ifEmpty { listOf(mapOf("start_utc" to Instant.ofEpochMilli(System.currentTimeMillis()).toString(), "end_utc" to Instant.ofEpochMilli(System.currentTimeMillis()).toString())) }
        segments.forEachIndexed { index, seg ->
            val start = seg["start_utc"]?.toString()?.let { Instant.parse(it) } ?: Instant.now()
            val end = seg["end_utc"]?.toString()?.let { Instant.parse(it) } ?: start
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
