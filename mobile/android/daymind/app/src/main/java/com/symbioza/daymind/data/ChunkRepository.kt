package com.symbioza.daymind.data

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import com.symbioza.daymind.audio.SpeechSegment
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ChunkRepository(private val context: Context) {
    private val chunksDir: File = File(context.cacheDir, "vault").apply { mkdirs() }
    private val externalDir: File = run {
        ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MUSIC)
            .firstOrNull()
            ?.let { File(it, "daymind_chunks") }
            ?: File(context.filesDir, "chunks_export")
    }.apply { mkdirs() }
    private val manifestFile = File(chunksDir, "chunks_manifest.json")
    private val manifest: MutableList<ChunkMetadata> = loadManifest()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()
    private val _latestChunkPath = MutableStateFlow<String?>(null)
    val latestChunkPath: StateFlow<String?> = _latestChunkPath.asStateFlow()
    private val _latestExternalPath = MutableStateFlow<String?>(null)
    val latestExternalPath: StateFlow<String?> = _latestExternalPath.asStateFlow()
    private val _chunkList = MutableStateFlow(manifest.sortedByDescending { it.createdAt })
    val chunkList: StateFlow<List<ChunkMetadata>> = _chunkList.asStateFlow()

    init {
        refresh()
    }

    fun newChunkFile(): File {
        val chunkFile = File(chunksDir, "chunk_${System.currentTimeMillis()}_${UUID.randomUUID()}.wav")
        if (!chunkFile.exists()) {
            chunkFile.parentFile?.mkdirs()
            chunkFile.createNewFile()
        }
        return chunkFile
    }

    private val externalStore = ExternalChunkStore(context)

    fun registerChunk(
        file: File,
        externalPath: String?,
        sessionStart: Instant,
        durationMs: Long,
        sampleRate: Int,
        speechSegments: List<SpeechSegment>
    ): ChunkMetadata {
        val publicPath = externalPath ?: externalStore.save(file, sessionStart.toEpochMilli())
        val data = ChunkMetadata(
            id = UUID.randomUUID().toString(),
            filePath = file.absolutePath,
            externalPath = publicPath ?: file.absolutePath,
            sessionStartUtc = sessionStart.toString(),
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            sampleRate = sampleRate,
            uploaded = false,
            speechSegments = speechSegments.map { SpeechSegmentJson(it.startMs, it.endMs) }
        )
        manifest.add(data)
        persistManifest()
        refresh()
        return data
    }

    fun markUploaded(ids: List<String>) {
        if (ids.isEmpty()) return
        manifest.replaceAll { meta ->
            if (ids.contains(meta.id)) meta.copy(uploaded = true) else meta
        }
        persistManifest()
        refresh()
    }

    fun pendingChunks(): List<ChunkMetadata> = manifest.filter { !it.uploaded }.sortedBy { it.createdAt }

    fun getManifestSnapshot(): List<ChunkMetadata> = manifest.toList()

    fun refresh() {
        _pendingCount.value = manifest.count { !it.uploaded }
        val latest = manifest.maxByOrNull { it.createdAt }
        _latestChunkPath.value = latest?.filePath
        _latestExternalPath.value = latest?.externalPath
        _chunkList.value = manifest.sortedByDescending { it.createdAt }
    }

    fun archiveDirectory(): File = File(chunksDir, "archives").apply { mkdirs() }

    private fun persistManifest() {
        val json = JSONArray()
        manifest.forEach { meta ->
            val obj = JSONObject().apply {
                put("id", meta.id)
                put("file_path", meta.filePath)
                put("external_path", meta.externalPath)
                put("session_start", meta.sessionStartUtc)
                put("created_at", meta.createdAt)
                put("duration_ms", meta.durationMs)
                put("sample_rate", meta.sampleRate)
                put("uploaded", meta.uploaded)
                put(
                    "speech_segments",
                    JSONArray().apply {
                        meta.speechSegments.forEach { segment ->
                            put(
                                JSONObject().apply {
                                    put("start_ms", segment.startMs)
                                    put("end_ms", segment.endMs)
                                }
                            )
                        }
                    }
                )
            }
            json.put(obj)
        }
        manifestFile.writeText(json.toString())
    }

    private fun loadManifest(): MutableList<ChunkMetadata> {
        if (!manifestFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(manifestFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val segments = obj.getJSONArray("speech_segments").let { segArray ->
                        buildList {
                            for (j in 0 until segArray.length()) {
                                val seg = segArray.getJSONObject(j)
                                add(
                                    SpeechSegmentJson(
                                        startMs = seg.getLong("start_ms"),
                                        endMs = seg.getLong("end_ms")
                                    )
                                )
                            }
                        }
                    }
                    add(
                        ChunkMetadata(
                            id = obj.getString("id"),
                            filePath = obj.getString("file_path"),
                            externalPath = obj.optString("external_path", obj.getString("file_path")),
                            sessionStartUtc = obj.getString("session_start"),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                            durationMs = obj.getLong("duration_ms"),
                            sampleRate = obj.getInt("sample_rate"),
                            uploaded = obj.optBoolean("uploaded", false),
                            speechSegments = segments
                        )
                    )
                }
            }.toMutableList()
        }.getOrElse { mutableListOf() }
    }
}
