package com.symbioza.daymind.audio

import com.symbioza.daymind.config.AudioSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class VoiceProcessingResult(
    val shouldSkip: Boolean,
    val processed: ShortArray,
    val voiceProbability: Float
)

class VoiceProcessingPipeline(private val sampleRate: Int) {
    private val denoiser = NoiseReducer(sampleRate)
    private val vad = AdaptiveVad(sampleRate)
    private val classifier = VoiceClassifier(sampleRate)

    fun process(buffer: ShortArray, length: Int, settings: AudioSettings): VoiceProcessingResult {
        val denoised = denoiser.process(buffer, length, settings.denoiseLevel)
        val vadResult = vad.score(denoised, length, settings)
        val probability = classifier.score(denoised, length, vadResult, settings)
        val shouldSkip = probability < settings.classifierSensitivity
        return VoiceProcessingResult(shouldSkip = shouldSkip, processed = denoised, voiceProbability = probability)
    }
}

private data class VadResult(
    val energyRatio: Float,
    val broadbandEnergy: Float,
    val spectralFlux: Float,
    val harmonicScore: Float
)

private class AdaptiveVad(sampleRate: Int) {
    private val spectralAnalyzer = SpectralAnalyzer(sampleRate)
    private val harmonicDetector = HarmonicDetector(sampleRate)

    fun score(samples: ShortArray, length: Int, settings: AudioSettings): VadResult {
        val spectral = spectralAnalyzer.analyze(samples, length)
        val harmonic = harmonicDetector.score(samples, length)
        val broadbandEnergy = spectral.totalEnergy
        val ratio = if (spectral.highBand <= 1f) 2f else (spectral.speechBand / spectral.highBand)
        val flux = spectral.flux
        val adjusted = (ratio * (1f + settings.voiceBias)).coerceAtMost(6f)
        return VadResult(
            energyRatio = adjusted,
            broadbandEnergy = broadbandEnergy,
            spectralFlux = flux,
            harmonicScore = harmonic
        )
    }
}

private data class SpectralSnapshot(
    val lowBand: Float,
    val midBand: Float,
    val highBand: Float,
    val airBand: Float,
    val flux: Float
) {
    val speechBand: Float get() = lowBand + midBand
    val totalEnergy: Float get() = lowBand + midBand + highBand + airBand
}

private class SpectralAnalyzer(private val sampleRate: Int) {
    private val fftSize = 512
    private val window = FloatArray(fftSize) { i ->
        (0.5f - 0.5f * cos(2f * PI.toFloat() * i / (fftSize - 1)))
    }
    private var previousSpectrum: FloatArray? = null

    fun analyze(samples: ShortArray, length: Int): SpectralSnapshot {
        val frame = FloatArray(fftSize) { 0f }
        val size = min(length, fftSize)
        for (i in 0 until size) {
            frame[i] = (samples[i] / 32768f) * window[i]
        }
        val imag = FloatArray(fftSize)
        fft(frame, imag)
        val magnitudes = FloatArray(fftSize / 2)
        for (i in 0 until magnitudes.size) {
            val re = frame[i]
            val im = imag[i]
            magnitudes[i] = sqrt(re * re + im * im)
        }
        val flux = computeFlux(magnitudes)
        val freqStep = sampleRate / fftSize.toFloat()
        var low = 0f
        var mid = 0f
        var high = 0f
        var air = 0f
        for (i in magnitudes.indices) {
            val freq = i * freqStep
            val energy = magnitudes[i].pow(2)
            when {
                freq < 200 -> low += energy
                freq < 1000 -> mid += energy
                freq < 3000 -> high += energy
                else -> air += energy
            }
        }
        return SpectralSnapshot(
            lowBand = low,
            midBand = mid,
            highBand = high,
            airBand = air,
            flux = flux
        )
    }

    private fun computeFlux(magnitudes: FloatArray): Float {
        val previous = previousSpectrum
        var flux = 0f
        if (previous != null && previous.size == magnitudes.size) {
            for (i in magnitudes.indices) {
                val diff = magnitudes[i] - previous[i]
                if (diff > 0) {
                    flux += diff
                }
            }
        }
        previousSpectrum = magnitudes.copyOf()
        return flux / (magnitudes.size.coerceAtLeast(1))
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j >= bit) {
                j -= bit
                bit = bit shr 1
            }
            j += bit
            if (i < j) {
                val tempReal = real[i]
                val tempImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempReal
                imag[j] = tempImag
            }
        }
        var length = 2
        while (length <= n) {
            val half = length / 2
            val angle = (-2.0 * Math.PI / length).toFloat()
            val wMulRe = cos(angle)
            val wMulIm = sin(angle)
            for (i in 0 until n step length) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val evenIndex = i + k
                    val oddIndex = i + k + half
                    val oddRe = real[oddIndex]
                    val oddIm = imag[oddIndex]
                    val tempRe = wRe * oddRe - wIm * oddIm
                    val tempIm = wRe * oddIm + wIm * oddRe
                    real[oddIndex] = real[evenIndex] - tempRe
                    imag[oddIndex] = imag[evenIndex] - tempIm
                    real[evenIndex] += tempRe
                    imag[evenIndex] += tempIm
                    val nextRe = wRe * wMulRe - wIm * wMulIm
                    val nextIm = wRe * wMulIm + wIm * wMulRe
                    wRe = nextRe
                    wIm = nextIm
                }
            }
            length *= 2
        }
    }
}

private class HarmonicDetector(private val sampleRate: Int) {
    private val minLag = (sampleRate / 400f).toInt().coerceAtLeast(10)
    private val maxLag = (sampleRate / 70f).toInt().coerceAtLeast(minLag + 10)
    private val bufferLength = sampleRate / 4

    fun score(samples: ShortArray, length: Int): Float {
        val size = min(length, bufferLength)
        if (size <= maxLag) return 0f
        var best = 0f
        val normalized = FloatArray(size) { samples[it] / 32768f }
        val energy = normalized.sumOf { (it * it).toDouble() }.toFloat().coerceAtLeast(1e-6f)
        for (lag in minLag..maxLag) {
            var sum = 0f
            var count = 0
            for (i in lag until size) {
                sum += normalized[i] * normalized[i - lag]
                count++
            }
            if (count == 0) continue
            val corr = (sum / count) / (energy / size)
            if (corr > best) {
                best = corr
            }
        }
        return best.coerceIn(0f, 1f)
    }
}

private class VoiceClassifier(private val sampleRate: Int) {
    private var noiseEnergy = 2_000f
    private var runningProbability = 0.5f

    fun score(samples: ShortArray, length: Int, vadResult: VadResult, settings: AudioSettings): Float {
        val averageEnergy = samples.take(length).sumOf { abs(it.toInt()) }.toFloat() / length.coerceAtLeast(1)
        noiseEnergy = (noiseEnergy * 0.92f) + (averageEnergy * 0.08f)
        val energyBoost = (averageEnergy / (noiseEnergy + 1f)).coerceAtMost(8f)
        val harmonic = vadResult.harmonicScore
        val flux = vadResult.spectralFlux
        val broadband = ln(1f + vadResult.broadbandEnergy * 1e-6f)
        val ratioScore = vadResult.energyRatio
        val score = (0.35f * ratioScore) +
            (0.25f * harmonic) +
            (0.2f * energyBoost) +
            (0.2f * flux.coerceIn(0f, 1f)) +
            (0.15f * broadband)
        val bias = (settings.voiceBias - 0.5f) * 1.2f
        val probability = sigmoid(score * 0.35f + bias)
        runningProbability = (runningProbability * 0.6f) + (probability * 0.4f)
        return runningProbability.coerceIn(0f, 1f)
    }

    private fun sigmoid(x: Float): Float {
        return (1f / (1f + kotlin.math.exp((-x).toDouble()))).toFloat()
    }
}
