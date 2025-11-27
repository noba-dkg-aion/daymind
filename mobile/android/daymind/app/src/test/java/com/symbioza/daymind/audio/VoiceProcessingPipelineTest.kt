package com.symbioza.daymind.audio

import com.symbioza.daymind.config.AudioSettings
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProcessingPipelineTest {
    private val sampleRate = 16_000
    private val pipeline = VoiceProcessingPipeline(sampleRate)
    private val settings = AudioSettings()

    @Test
    fun `pipeline retains speech under moderate synthetic noise`() {
        val speech = SynthSpeechGenerator(sampleRate).generate("DayMind voice check sample", 0.85f)
        val noisy = AudioNoiseMixer.mix(
            speech,
            profile = NoiseProfile.CAFE,
            snrDb = 8.0,
            seed = 42
        )
        val result = pipeline.process(noisy, noisy.size, settings)
        assertFalse("speech frame should not be skipped", result.shouldSkip)
        assertTrue("voice probability should stay high", result.voiceProbability > 0.55f)
        val trimmed = trimToSegments(result.processed)
        assertTrue("speech segments should remain after trimming", trimmed.keptSamples > sampleRate / 5)
        assertTrue("at least one speech segment detected", trimmed.segments.isNotEmpty())
    }

    @Test
    fun `pipeline skips frames made only of heavy noise`() {
        val noiseOnly = AudioNoiseMixer.generateNoiseSamples(
            size = sampleRate * 2,
            profile = NoiseProfile.HUM,
            amplitude = 0.9f,
            seed = 7
        )
        val result = pipeline.process(noiseOnly, noiseOnly.size, settings)
        assertTrue("pure noise should be skipped", result.shouldSkip)
        assertTrue("voice probability should be low", result.voiceProbability < 0.3f)
    }

    private fun trimToSegments(samples: ShortArray): TrimResult {
        val tempFile = File.createTempFile("daymind_test", ".wav")
        return try {
            val writer = WavWriter(tempFile, sampleRate)
            writer.write(samples, samples.size)
            writer.close()
            SilenceTrimmer.trim(
                file = tempFile,
                sampleRate = sampleRate,
                threshold = 1800,
                aggressiveness = 2
            )
        } finally {
            tempFile.delete()
        }
    }
}

private class SynthSpeechGenerator(
    private val sampleRate: Int
) {
    fun generate(text: String, amplitude: Float = 0.9f): ShortArray {
        val sanitized = text.ifBlank { "DayMind" }
        val perCharMs = 140
        val totalSamples = sanitized.length * perCharMs * sampleRate / 1000
        val buffer = ShortArray(totalSamples)
        var index = 0
        sanitized.forEach { ch ->
            val duration = perCharMs * sampleRate / 1000
            val baseFreq = 180f + ((ch.code % 20) * 7)
            val formant = baseFreq * 2.2f
            for (i in 0 until duration) {
                val t = i.toFloat() / sampleRate
                val env = envelope(i, duration)
                val sample = env * (
                    sin(2 * PI.toFloat() * baseFreq * t) * 0.7f +
                        sin(2 * PI.toFloat() * formant * t) * 0.3f
                    )
                buffer[index] = (amplitude * sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                index += 1
            }
        }
        return buffer
    }

    private fun envelope(i: Int, duration: Int): Float {
        val attack = (duration * 0.1f).coerceAtLeast(1f)
        val release = duration - attack
        return when {
            i < attack -> (i / attack)
            i > release -> (duration - i) / attack
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}

private enum class NoiseProfile {
    WHITE,
    HUM,
    CAFE
}

private object AudioNoiseMixer {
    fun mix(
        speech: ShortArray,
        profile: NoiseProfile,
        snrDb: Double,
        seed: Long = 0
    ): ShortArray {
        val noise = generateNoiseSamples(speech.size, profile, amplitude = 1f, seed = seed)
        val signalRms = rms(speech)
        val targetNoiseRms = signalRms / 10.0.pow(snrDb / 20.0)
        val noiseRms = rms(noise)
        val gain = if (noiseRms == 0.0) 0.0 else targetNoiseRms / noiseRms
        val mixed = ShortArray(speech.size)
        for (i in speech.indices) {
            val sample = speech[i].toFloat() + (noise[i].toFloat() * gain).toFloat()
            mixed[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return mixed
    }

    fun generateNoiseSamples(
        size: Int,
        profile: NoiseProfile,
        amplitude: Float,
        seed: Long = 0
    ): ShortArray {
        val rng = Random(seed)
        val buffer = ShortArray(size)
        when (profile) {
            NoiseProfile.WHITE -> {
                repeat(size) { idx ->
                    buffer[idx] = (rng.nextFloat() * 2f - 1f).times(amplitude * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
            }
            NoiseProfile.HUM -> {
                val humFreq = 60f
                repeat(size) { idx ->
                    val t = idx.toFloat() / 16_000f
                    val hum = sin(2 * PI.toFloat() * humFreq * t)
                    buffer[idx] = (hum * amplitude * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            NoiseProfile.CAFE -> {
                val voices = (1..4).map {
                    val freq = 140f + rng.nextFloat() * 80f
                    val phase = rng.nextFloat() * PI.toFloat()
                    Pair(freq, phase)
                }
                repeat(size) { idx ->
                    val t = idx.toFloat() / 16_000f
                    var sample = 0f
                    voices.forEach { (freq, phase) ->
                        sample += sin(2 * PI.toFloat() * freq * t + phase) * 0.25f
                    }
                    sample += (rng.nextFloat() * 2f - 1f) * 0.15f
                    buffer[idx] = (sample * amplitude * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
            }
        }
        return buffer
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        val acc = samples.fold(0.0) { sum, value ->
            sum + (value.toDouble() / 32768.0).pow(2.0)
        }
        return kotlin.math.sqrt(acc / samples.size)
    }
}
