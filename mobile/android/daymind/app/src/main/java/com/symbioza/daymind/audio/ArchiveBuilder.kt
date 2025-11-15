package com.symbioza.daymind.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import com.symbioza.daymind.data.ArchiveManifest
import com.symbioza.daymind.data.ArchiveManifestChunk
import com.symbioza.daymind.data.ChunkMetadata
import com.symbioza.daymind.data.SpeechWindowUtc
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ArchiveBuildResult(
    val archiveFile: File,
    val manifestFile: File,
    val manifest: ArchiveManifest,
    val shareableFile: File
)

class ArchiveBuilder(private val context: Context) {
    fun buildArchive(chunks: List<ChunkMetadata>, targetDir: File): ArchiveBuildResult {
        require(chunks.isNotEmpty())
        val archiveId = UUID.randomUUID().toString()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val tempDir = File(targetDir, "tmp").apply { mkdirs() }
        val wavFile = File(tempDir, "archive_${archiveId}.wav")
        val flacFile = File(targetDir, "archive_${archiveId}.flac")
        val manifestFile = File(targetDir, "archive_${archiveId}.json")

        writeConcatenatedWav(chunks, wavFile)
        encodeFlac(wavFile, flacFile, chunks.first().sampleRate)
        wavFile.delete()

        val manifest = ArchiveManifest(
            archiveId = archiveId,
            generatedUtc = timestamp,
            chunkCount = chunks.size,
            chunks = chunks.map { chunk ->
                val baseStart = Instant.parse(chunk.sessionStartUtc)
                ArchiveManifestChunk(
                    chunkId = chunk.id,
                    sessionStartUtc = chunk.sessionStartUtc,
                    sessionEndUtc = chunk.sessionEndUtc,
                    speechSegmentsUtc = chunk.speechSegments.map { segment ->
                        SpeechWindowUtc(
                            startUtc = baseStart.plusMillis(segment.startMs).toString(),
                            endUtc = baseStart.plusMillis(segment.endMs).toString()
                        )
                    }
                )
            }
        )
        manifestFile.writeText(manifest.toJson())

        val externalMusicDir =
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.apply { mkdirs() }
                ?: File(context.filesDir, "exports").apply { mkdirs() }
        val shareFile = File(externalMusicDir, flacFile.name)
        flacFile.copyTo(shareFile, overwrite = true)

        return ArchiveBuildResult(
            archiveFile = flacFile,
            manifestFile = manifestFile,
            manifest = manifest,
            shareableFile = shareFile
        )
    }

    private fun writeConcatenatedWav(chunks: List<ChunkMetadata>, wavFile: File) {
        val writer = WavWriter(wavFile, sampleRate = chunks.first().sampleRate)
        chunks.forEach { chunk ->
            val source = File(chunk.filePath)
            if (source.extension.equals("flac", ignoreCase = true)) {
                decodeFlac(source, writer)
            } else {
                appendWav(source, writer)
            }
        }
        writer.close()
    }

    private fun appendWav(file: File, writer: WavWriter) {
        val buffer = ByteArray(8192)
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.skip(HEADER_BYTES.toLong())
            val shortBuffer = ShortArray(buffer.size / 2)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val samples = read / 2
                var index = 0
                for (i in 0 until samples) {
                    val low = buffer[index].toInt() and 0xFF
                    val high = buffer[index + 1].toInt()
                    shortBuffer[i] = ((high shl 8) or low).toShort()
                    index += 2
                }
                writer.write(shortBuffer, samples)
            }
        }
    }

    private fun decodeFlac(file: File, writer: WavWriter) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) {
                trackIndex = i
                break
            }
        }
        if (trackIndex == -1) {
            extractor.release()
            throw IllegalStateException("No FLAC track found in ${file.name}")
        }
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    if (bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(bytes)
                        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val pcm = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(pcm)
                        writer.write(pcm, pcm.size)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        outputDone = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // ignore
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
    }

    private fun encodeFlac(wavFile: File, flacFile: File, sampleRate: Int) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC,
            sampleRate,
            1
        ).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        FileInputStream(wavFile).use { input ->
            input.skip(HEADER_BYTES.toLong())
            FileOutputStream(flacFile).use { output ->
                val inputBuffer = ByteArray(8192)
                var inputEnded = false
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            buffer.clear()
                            val read = input.read(inputBuffer)
                            if (read == -1) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                buffer.put(inputBuffer, 0, read)
                                codec.queueInputBuffer(inputIndex, 0, read, 0, 0)
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outputIndex)!!
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.get(chunk)
                            output.write(chunk)
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                codec.stop()
                                codec.release()
                                return
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // ignore
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputEnded) {
                                continue
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ArchiveManifest.toJson(): String {
        val chunksJson = chunks.joinToString(prefix = "[", postfix = "]") { chunk ->
            val segments = chunk.speechSegmentsUtc.joinToString(prefix = "[", postfix = "]") { seg ->
                "{\"start_utc\":\"${seg.startUtc}\",\"end_utc\":\"${seg.endUtc}\"}"
            }
            """{
                "chunk_id":"${chunk.chunkId}",
                "session_start":"${chunk.sessionStartUtc}",
                "session_end":"${chunk.sessionEndUtc}",
                "speech_segments":$segments
            }""".trimIndent()
        }
        return """{
            "archive_id":"$archiveId",
            "generated_utc":"$generatedUtc",
            "chunk_count":$chunkCount,
            "chunks":$chunksJson
        }""".trimIndent()
    }

    companion object {
        private const val HEADER_BYTES = 44
        private const val TIMEOUT_US = 10_000L
    }
}
