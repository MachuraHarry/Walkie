package com.ronin.walkie.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Zentrale Einstellungsverwaltung für die Walkie-App.
 * Alle Einstellungen werden in SharedPreferences persistiert.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val TAG = "SettingsManager"
        private const val PREFS_NAME = "walkie_settings"

        // Keys
        const val KEY_USERNAME = "username"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_PTT_MODE = "ptt_mode" // "hold" oder "toggle"
        const val KEY_AUDIO_QUALITY = "audio_quality" // sample rate: 8000, 16000, 32000, 44100
        const val KEY_VAD_ENABLED = "vad_enabled"
        const val KEY_VAD_THRESHOLD = "vad_threshold"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DARK_MODE = "dark_mode" // "system", "light", "dark"
        const val KEY_SPEAKER_DEFAULT = "speaker_default" // true = Lautsprecher an
        const val KEY_AUDIO_COMPRESSION = "audio_compression"
        const val KEY_PTT_TOGGLE_LOCK_THRESHOLD = "ptt_toggle_lock_threshold" // in dp
        const val KEY_LANGUAGE = "language" // "de" oder "en"

        // Defaults
        const val DEFAULT_USERNAME = ""
        const val DEFAULT_SOUND_ENABLED = true
        const val DEFAULT_PTT_MODE = "hold"
        const val DEFAULT_AUDIO_QUALITY = 16000
        const val DEFAULT_VAD_ENABLED = true
        const val DEFAULT_VAD_THRESHOLD = 500
        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_DARK_MODE = "system"
        const val DEFAULT_SPEAKER_DEFAULT = true
        const val DEFAULT_AUDIO_COMPRESSION = false
        const val DEFAULT_PTT_TOGGLE_LOCK_THRESHOLD = 80
        const val DEFAULT_LANGUAGE = "de"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        Log.d(TAG, "🏗️ SettingsManager initialized")
    }

    // ===== Benutzername =====
    fun getUsername(): String = prefs.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME
    fun setUsername(username: String) {
        Log.d(TAG, "👤 Setting username: '$username'")
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    // ===== Sound-Effekte =====
    fun isSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND_ENABLED, DEFAULT_SOUND_ENABLED)
    fun setSoundEnabled(enabled: Boolean) {
        Log.d(TAG, "🔊 Sound effects: $enabled")
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    // ===== PTT Modus =====
    fun getPttMode(): String = prefs.getString(KEY_PTT_MODE, DEFAULT_PTT_MODE) ?: DEFAULT_PTT_MODE
    fun isPttToggleMode(): Boolean = getPttMode() == "toggle"
    fun setPttMode(mode: String) {
        Log.d(TAG, "🔘 PTT mode: $mode")
        prefs.edit().putString(KEY_PTT_MODE, mode).apply()
    }

    // ===== Audio-Qualität (Sample-Rate) =====
    fun getAudioQuality(): Int = prefs.getInt(KEY_AUDIO_QUALITY, DEFAULT_AUDIO_QUALITY)
    fun setAudioQuality(sampleRate: Int) {
        Log.d(TAG, "🎵 Audio quality: ${sampleRate}Hz")
        prefs.edit().putInt(KEY_AUDIO_QUALITY, sampleRate).apply()
    }

    // ===== VAD (Voice Activity Detection) =====
    fun isVadEnabled(): Boolean = prefs.getBoolean(KEY_VAD_ENABLED, DEFAULT_VAD_ENABLED)
    fun setVadEnabled(enabled: Boolean) {
        Log.d(TAG, "🔇 VAD: $enabled")
        prefs.edit().putBoolean(KEY_VAD_ENABLED, enabled).apply()
    }

    fun getVadThreshold(): Int = prefs.getInt(KEY_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD)
    fun setVadThreshold(threshold: Int) {
        Log.d(TAG, "🔇 VAD threshold: $threshold")
        prefs.edit().putInt(KEY_VAD_THRESHOLD, threshold).apply()
    }

    // ===== Server URL =====
    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    fun setServerUrl(url: String) {
        Log.d(TAG, "🌐 Server URL: '$url'")
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    // ===== Dark Mode =====
    fun getDarkMode(): String = prefs.getString(KEY_DARK_MODE, DEFAULT_DARK_MODE) ?: DEFAULT_DARK_MODE
    fun setDarkMode(mode: String) {
        Log.d(TAG, "🌙 Dark mode: $mode")
        prefs.edit().putString(KEY_DARK_MODE, mode).apply()
    }

    // ===== Speaker Default =====
    fun isSpeakerDefault(): Boolean = prefs.getBoolean(KEY_SPEAKER_DEFAULT, DEFAULT_SPEAKER_DEFAULT)
    fun setSpeakerDefault(on: Boolean) {
        Log.d(TAG, "🔊 Speaker default: $on")
        prefs.edit().putBoolean(KEY_SPEAKER_DEFAULT, on).apply()
    }

    // ===== Audio Compression =====
    fun isAudioCompressionEnabled(): Boolean = prefs.getBoolean(KEY_AUDIO_COMPRESSION, DEFAULT_AUDIO_COMPRESSION)
    fun setAudioCompression(enabled: Boolean) {
        Log.d(TAG, "🗜️ Audio compression: $enabled")
        prefs.edit().putBoolean(KEY_AUDIO_COMPRESSION, enabled).apply()
    }

    // ===== PTT Toggle Lock Threshold =====
    fun getPttToggleLockThreshold(): Int = prefs.getInt(KEY_PTT_TOGGLE_LOCK_THRESHOLD, DEFAULT_PTT_TOGGLE_LOCK_THRESHOLD)
    fun setPttToggleLockThreshold(threshold: Int) {
        Log.d(TAG, "🔒 PTT toggle lock threshold: ${threshold}dp")
        prefs.edit().putInt(KEY_PTT_TOGGLE_LOCK_THRESHOLD, threshold).apply()
    }

    // ===== Sprache =====
    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    fun setLanguage(language: String) {
        Log.d(TAG, "🌐 Setting language: $language")
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    // ===== Alle Einstellungen zurücksetzen =====
    fun resetAllSettings() {
        Log.d(TAG, "🔄 Resetting all settings to defaults")
        prefs.edit().clear().apply()
    }

    // ===== Convenience: Alle Einstellungen als Map =====
    fun getAllSettings(): Map<String, Any?> = mapOf(
        KEY_USERNAME to getUsername(),
        KEY_SOUND_ENABLED to isSoundEnabled(),
        KEY_PTT_MODE to getPttMode(),
        KEY_AUDIO_QUALITY to getAudioQuality(),
        KEY_VAD_ENABLED to isVadEnabled(),
        KEY_VAD_THRESHOLD to getVadThreshold(),
        KEY_SERVER_URL to getServerUrl(),
        KEY_DARK_MODE to getDarkMode(),
        KEY_SPEAKER_DEFAULT to isSpeakerDefault(),
        KEY_AUDIO_COMPRESSION to isAudioCompressionEnabled(),
        KEY_PTT_TOGGLE_LOCK_THRESHOLD to getPttToggleLockThreshold()
    )
}
