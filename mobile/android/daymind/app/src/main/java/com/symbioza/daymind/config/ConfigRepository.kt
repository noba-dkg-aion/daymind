package com.symbioza.daymind.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.symbioza.daymind.BuildConfig

data class AudioSettings(
    val vadThreshold: Int = 3500,
    val vadAggressiveness: Int = 2,
    val noiseGate: Float = 0.12f,
    val voiceBias: Float = 0.5f,
    val denoiseLevel: Float = 0.6f,
    val classifierSensitivity: Float = 0.55f
)

class ConfigRepository(context: Context) {
    private val encryptedPrefs = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    fun getServerUrl(): String = readOrDefault(KEY_SERVER_URL, BuildConfig.BASE_URL)

    fun getApiKey(): String = readOrDefault(KEY_API_KEY, BuildConfig.API_KEY)

    fun getAudioSettings(): AudioSettings {
        return AudioSettings(
            vadThreshold = encryptedPrefs?.getInt(KEY_VAD_THRESHOLD, 3500) ?: 3500,
            vadAggressiveness = encryptedPrefs?.getInt(KEY_VAD_AGGRESSIVENESS, 2) ?: 2,
            noiseGate = encryptedPrefs?.getFloat(KEY_NOISE_GATE, 0.12f) ?: 0.12f,
            voiceBias = encryptedPrefs?.getFloat(KEY_VOICE_BIAS, 0.5f) ?: 0.5f,
            denoiseLevel = encryptedPrefs?.getFloat(KEY_DENOISE_LEVEL, 0.6f) ?: 0.6f,
            classifierSensitivity = encryptedPrefs?.getFloat(KEY_CLASSIFIER_SENS, 0.55f) ?: 0.55f
        )
    }

    fun saveVadThreshold(value: Int) {
        encryptedPrefs?.edit()?.putInt(KEY_VAD_THRESHOLD, value)?.apply()
    }

    fun saveVadAggressiveness(value: Int) {
        encryptedPrefs?.edit()?.putInt(KEY_VAD_AGGRESSIVENESS, value)?.apply()
    }

    fun saveNoiseGate(value: Float) {
        encryptedPrefs?.edit()?.putFloat(KEY_NOISE_GATE, value)?.apply()
    }

    fun saveVoiceBias(value: Float) {
        encryptedPrefs?.edit()?.putFloat(KEY_VOICE_BIAS, value)?.apply()
    }

    fun saveDenoiseLevel(value: Float) {
        encryptedPrefs?.edit()?.putFloat(KEY_DENOISE_LEVEL, value)?.apply()
    }

    fun saveClassifierSensitivity(value: Float) {
        encryptedPrefs?.edit()?.putFloat(KEY_CLASSIFIER_SENS, value)?.apply()
    }

    private fun readOrDefault(key: String, defaultValue: String): String {
        val value = encryptedPrefs?.getString(key, null)
        return value?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    fun saveServerUrl(value: String) {
        encryptedPrefs?.edit()?.putString(KEY_SERVER_URL, value)?.apply()
    }

    fun saveApiKey(value: String) {
        encryptedPrefs?.edit()?.putString(KEY_API_KEY, value)?.apply()
    }

    companion object {
        private const val PREFS_NAME = "daymind.secrets"
        private const val KEY_SERVER_URL = "SERVER_URL"
        private const val KEY_API_KEY = "API_KEY"
        private const val KEY_VAD_THRESHOLD = "VAD_THRESHOLD"
        private const val KEY_VAD_AGGRESSIVENESS = "VAD_AGGRESSIVENESS"
        private const val KEY_NOISE_GATE = "NOISE_GATE"
        private const val KEY_VOICE_BIAS = "VOICE_BIAS"
        private const val KEY_DENOISE_LEVEL = "DENOISE_LEVEL"
        private const val KEY_CLASSIFIER_SENS = "CLASSIFIER_SENSITIVITY"
    }
}
