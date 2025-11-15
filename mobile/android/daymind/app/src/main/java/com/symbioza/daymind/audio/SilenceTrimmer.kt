package com.symbioza.daymind.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SpeechSegment(val startMs: Long, val endMs: Long)

data class TrimResult(
    val keptSamples: Int,
    val segments: List<SpeechSegment>,
    val samples: ShortArray
)

object SilenceTrimmer {
    private const val HEADER_BYTES = 44
    private const val DEFAULT_THRESHOLD = 1200
    private const val DEFAULT_MIN_SPEECH_MS = 250L
    private const val DEFAULT_MIN_SILENCE_MS = 350L
    private const val DEFAULT_PADDING_MS = 150L
    private const val DEFAULT_AGGRESSIVENESS = 2
    private const val FRAME_MS = 30L
    private const val HOP_MS = 15L
    private const val PRE_EMPHASIS = 0.97f
    private const val ENERGY_FACTOR = 2.5f
    private const val MIN_ENERGY = 5_000f
    private const val MIN_ZCR = 0.01f
    private const val MAX_ZCR = 0.25f

    fun trim(
        file: File,
        sampleRate: Int,
        threshold: Int = DEFAULT_THRESHOLD,
        minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
        minSilenceMs: Long = DEFAULT_MIN_SILENCE_MS,
        paddingMs: Long = DEFAULT_PADDING_MS,
        aggressiveness: Int = DEFAULT_AGGRESSIVENESS
    ): TrimResult {
        val bytes = file.readBytes()
        if (bytes.size <= HEADER_BYTES) {
            return TrimResult(0, emptyList(), ShortArray(0))
        }
        val samples = extractSamples(bytes)
        val adjustedThreshold = max(500, threshold)
        val energyMultiplier = 1f + (aggressiveness.coerceIn(0, 3) * 0.4f)
        val sampleSegments = detectSegments(
            samples,
            sampleRate,
            adjustedThreshold,
            minSpeechMs,
            minSilenceMs,
            energyMultiplier
        )
        if (sampleSegments.isEmpty()) {
            return TrimResult(0, emptyList(), ShortArray(0))
        }
        val trimmedSamples = collectTrimmedSamples(samples, sampleSegments, sampleRate, paddingMs)
        val speechSegments = sampleSegments.map { segment ->
            SpeechSegment(
                startMs = samplesToMs(segment.start, sampleRate),
                endMs = samplesToMs(segment.end, sampleRate)
            )
        }
        return TrimResult(trimmedSamples.size, speechSegments, trimmedSamples)
    }

    private fun extractSamples(bytes: ByteArray): ShortArray {
        val sampleCount = (bytes.size - HEADER_BYTES) / 2
        val buffer = ByteBuffer.wrap(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            samples[i] = buffer.short
        }
        return samples
    }

    private data class SampleSegment(val start: Int, val end: Int)

    private fun detectSegments(
        samples: ShortArray,
        sampleRate: Int,
        threshold: Int,
        minSpeechMs: Long,
        minSilenceMs: Long,
        energyMultiplier: Float
    ): List<SampleSegment> {
        val frameSamples = (FRAME_MS * sampleRate / 1000).toInt().coerceAtLeast(1)
        val hopSamples = (HOP_MS * sampleRate / 1000).toInt().coerceAtLeast(1)
        if (samples.size < frameSamples) {
            return detectByAmplitude(samples, sampleRate, threshold, minSpeechMs, minSilenceMs)
        }
        val minSpeechSamples = (minSpeechMs * sampleRate / 1000).toInt().coerceAtLeast(1)
        val minSilenceFrames = (minSilenceMs / HOP_MS).toInt().coerceAtLeast(1)
        val flags = mutableListOf<Boolean>()
        var offset = 0
        var noiseFloor = threshold.toFloat() * threshold
        while (offset + frameSamples <= samples.size) {
            val energy = frameEnergy(samples, offset, frameSamples)
            val zcr = frameZeroCrossRate(samples, offset, frameSamples)
            val dynamicThreshold = max(MIN_ENERGY, noiseFloor * (ENERGY_FACTOR * energyMultiplier))
            val speech = energy > dynamicThreshold && zcr in MIN_ZCR..MAX_ZCR
            flags.add(speech)
            if (!speech) {
                noiseFloor = (noiseFloor * 0.9f) + (energy * 0.1f)
            }
            offset += hopSamples
        }
        if (flags.isEmpty()) {
            return detectByAmplitude(samples, sampleRate, threshold, minSpeechMs, minSilenceMs)
        }
        val segments = mutableListOf<SampleSegment>()
        var currentStart: Int? = null
        var silenceFrames = 0
        flags.forEachIndexed { index, isSpeech ->
            if (isSpeech) {
                if (currentStart == null) {
                    currentStart = index
                }
                silenceFrames = 0
            } else if (currentStart != null) {
                silenceFrames += 1
                if (silenceFrames >= minSilenceFrames) {
                    val startSample = currentStart!! * hopSamples
                    val endSample = min(samples.size - 1, (index * hopSamples) + frameSamples)
                    if (endSample - startSample >= minSpeechSamples) {
                        segments.add(SampleSegment(startSample, endSample))
                    }
                    currentStart = null
                    silenceFrames = 0
                }
            }
        }
        currentStart?.let { startFrame ->
            val startSample = startFrame * hopSamples
            val endSample = samples.size - 1
            if (endSample - startSample >= minSpeechSamples) {
                segments.add(SampleSegment(startSample, endSample))
            }
        }
        return if (segments.isNotEmpty()) segments else detectByAmplitude(samples, sampleRate, threshold, minSpeechMs, minSilenceMs)
    }

    private fun detectByAmplitude(
        samples: ShortArray,
        sampleRate: Int,
        threshold: Int,
        minSpeechMs: Long,
        minSilenceMs: Long
    ): List<SampleSegment> {
        val minSpeechSamples = (minSpeechMs * sampleRate / 1000).toInt().coerceAtLeast(1)
        val minSilenceSamples = (minSilenceMs * sampleRate / 1000).toInt().coerceAtLeast(1)
        val segments = mutableListOf<SampleSegment>()
        var isSpeaking = false
        var segmentStart = 0
        var lastSpeechSample = -1
        var silenceCounter = 0
        samples.forEachIndexed { index, sample ->
            val amplitude = abs(sample.toInt())
            if (amplitude >= threshold) {
                if (!isSpeaking) {
                    isSpeaking = true
                    segmentStart = index
                }
                lastSpeechSample = index
                silenceCounter = 0
            } else if (isSpeaking) {
                silenceCounter += 1
                if (silenceCounter >= minSilenceSamples) {
                    val endSample = max(segmentStart, lastSpeechSample)
                    if (endSample - segmentStart >= minSpeechSamples) {
                        segments.add(SampleSegment(segmentStart, endSample))
                    }
                    isSpeaking = false
                    silenceCounter = 0
                }
            }
        }
        if (isSpeaking) {
            val endSample = max(segmentStart, lastSpeechSample.takeIf { it >= 0 } ?: samples.lastIndex)
            if (endSample - segmentStart >= minSpeechSamples) {
                segments.add(SampleSegment(segmentStart, endSample))
            }
        }
        return segments
    }

    private fun frameEnergy(samples: ShortArray, start: Int, length: Int): Float {
        var energy = 0f
        var prev = samples.getOrElse(start - 1) { samples[start] }.toFloat()
        for (i in 0 until length) {
            val sample = samples[start + i].toFloat()
            val emphasized = sample - PRE_EMPHASIS * prev
            prev = sample
            energy += emphasized * emphasized
        }
        return energy / length
    }

    private fun frameZeroCrossRate(samples: ShortArray, start: Int, length: Int): Float {
        var crossings = 0
        for (i in 1 until length) {
            val prev = samples[start + i - 1]
            val current = samples[start + i]
            if ((current >= 0 && prev < 0) || (current < 0 && prev >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / length
    }

    private fun collectTrimmedSamples(
        samples: ShortArray,
        segments: List<SampleSegment>,
        sampleRate: Int,
        paddingMs: Long
    ): ShortArray {
        val padSamples = (paddingMs * sampleRate / 1000).toInt().coerceAtLeast(0)
        val totalSamples = segments.sumOf { segment ->
            val start = max(0, segment.start - padSamples)
            val end = min(samples.size - 1, segment.end + padSamples)
            (end - start + 1).coerceAtLeast(0)
        }
        if (totalSamples <= 0) return ShortArray(0)

        val trimmed = ShortArray(totalSamples)
        var writeIndex = 0
        segments.forEach { segment ->
            val start = max(0, segment.start - padSamples)
            val end = min(samples.size - 1, segment.end + padSamples)
            for (i in start..end) {
                trimmed[writeIndex++] = samples[i]
            }
        }
        return trimmed
    }

    private fun samplesToMs(sampleIndex: Int, sampleRate: Int): Long {
        return (sampleIndex * 1000L) / sampleRate
    }
}
