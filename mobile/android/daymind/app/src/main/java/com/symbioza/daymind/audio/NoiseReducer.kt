package com.symbioza.daymind.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class NoiseReducer(
    private val sampleRate: Int,
    private val cutoffHz: Float = 120f,
    private val smoothing: Float = 0.9f
) {
    private var prevInput = 0f
    private var prevOutput = 0f

    fun process(buffer: ShortArray, length: Int, level: Float = 0.6f): ShortArray {
        val floatData = FloatArray(length)
        for (i in 0 until length) {
            floatData[i] = buffer[i].toFloat()
        }
        val filtered = highPass(floatData)
        val gated = spectralGate(filtered, level.coerceIn(0.2f, 1f))
        val warmup = min(length, max(1, (sampleRate * 0.01f).toInt()))
        for (i in 0 until warmup) {
            gated[i] = 0f
        }
        val minimal = 60f
        val result = ShortArray(length)
        for (i in 0 until length) {
            val sample = if (abs(gated[i]) < minimal) 0f else gated[i]
            val clamped = sample.coerceIn(-32768f, 32767f)
            result[i] = clamped.toInt().toShort()
        }
        return result
    }

    private fun highPass(data: FloatArray): FloatArray {
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val dt = 1f / sampleRate
        val alpha = rc / (rc + dt)
        val output = FloatArray(data.size)
        var prevIn = prevInput
        var prevOut = prevOutput
        for (i in data.indices) {
            val x = data[i]
            val y = alpha * (prevOut + x - prevIn)
            output[i] = y
            prevOut = y
            prevIn = x
        }
        prevInput = prevIn
        prevOutput = prevOut
        return output
    }

    private fun spectralGate(data: FloatArray, level: Float): FloatArray {
        val window = max(64, (sampleRate * 0.02f).toInt())
        val output = FloatArray(data.size)
        var noiseFloor = 0f
        var currentFloor = 0f
        for (i in data.indices) {
            val sample = data[i]
            noiseFloor = (smoothing * noiseFloor) + ((1 - smoothing) * sample * sample)
            if (i % window == 0) {
                currentFloor = sqrt(noiseFloor)
            }
            val threshold = currentFloor * (1.2f + (1.2f * level))
            output[i] = if (abs(sample) <= threshold) 0f else sample
        }
        return output
    }
}
