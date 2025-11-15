package com.symbioza.daymind.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class FlacEncoder(
    private val sampleRate: Int,
    private val channelCount: Int = 1,
    private val compressionLevel: Int = 5
) {
    fun encode(samples: ShortArray, outputFile: File) {
        if (!outputFile.parentFile.exists()) {
            outputFile.parentFile?.mkdirs()
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, compressionLevel)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val byteBuffer = ByteBuffer.allocate(samples.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { byteBuffer.putShort(it) }
        byteBuffer.flip()

        FileOutputStream(outputFile).use { output ->
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()
                        val remaining = byteBuffer.remaining()
                        if (remaining > 0) {
                            val toWrite = min(remaining, inputBuffer.capacity())
                            val chunk = ByteArray(toWrite)
                            byteBuffer.get(chunk)
                            inputBuffer.put(chunk)
                            codec.queueInputBuffer(inputIndex, 0, toWrite, 0, 0)
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            output.write(data)
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
                        // no-op
                    }
                }
            }
        }
        codec.stop()
        codec.release()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
