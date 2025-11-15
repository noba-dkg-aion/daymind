package com.symbioza.daymind.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecordingStateStore {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _voiceLevel = MutableStateFlow(0f)
    val voiceLevel: StateFlow<Float> = _voiceLevel.asStateFlow()

    fun markRecording() {
        _isRecording.value = true
    }

    fun markStopped() {
        _isRecording.value = false
        _voiceLevel.value = 0f
    }

    fun updateVoiceLevel(probability: Float) {
        _voiceLevel.value = probability.coerceIn(0f, 1f)
    }
}
