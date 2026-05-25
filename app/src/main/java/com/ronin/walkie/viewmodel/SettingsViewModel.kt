package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.walkie.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val username: String = "",
    val isSoundEnabled: Boolean = true,
    val pttMode: String = "hold", // "hold" oder "toggle"
    val audioQuality: Int = 16000,
    val isVadEnabled: Boolean = true,
    val vadThreshold: Int = 500,
    val serverUrl: String = "",
    val darkMode: String = "system", // "system", "light", "dark"
    val isSpeakerDefault: Boolean = true,
    val isAudioCompressionEnabled: Boolean = false,
    val pttToggleLockThreshold: Int = 80,
    val language: String = "de", // "de" oder "en"
    val isUsernameEditing: Boolean = false,
    val isServerUrlEditing: Boolean = false,
    val showResetDialog: Boolean = false,
    val showRestartRequired: Boolean = false,
    val savedMessage: String? = null
)

class SettingsViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsVM"
    }

    private val settingsManager = SettingsManager(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "🏗️ SettingsViewModel created")
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.value = SettingsUiState(
            username = settingsManager.getUsername(),
            isSoundEnabled = settingsManager.isSoundEnabled(),
            pttMode = settingsManager.getPttMode(),
            audioQuality = settingsManager.getAudioQuality(),
            isVadEnabled = settingsManager.isVadEnabled(),
            vadThreshold = settingsManager.getVadThreshold(),
            serverUrl = settingsManager.getServerUrl(),
            darkMode = settingsManager.getDarkMode(),
            isSpeakerDefault = settingsManager.isSpeakerDefault(),
            isAudioCompressionEnabled = settingsManager.isAudioCompressionEnabled(),
            pttToggleLockThreshold = settingsManager.getPttToggleLockThreshold(),
            language = settingsManager.getLanguage()
        )
        Log.d(TAG, "📋 Settings loaded: ${settingsManager.getAllSettings()}")
    }

    // ===== Benutzername =====
    fun startEditingUsername() {
        _uiState.value = _uiState.value.copy(isUsernameEditing = true)
    }

    fun cancelEditingUsername() {
        _uiState.value = _uiState.value.copy(
            isUsernameEditing = false,
            username = settingsManager.getUsername()
        )
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun saveUsername() {
        val username = _uiState.value.username.trim()
        if (username.length >= 2) {
            settingsManager.setUsername(username)
            _uiState.value = _uiState.value.copy(
                isUsernameEditing = false,
                savedMessage = "✅ Benutzername gespeichert"
            )
            clearSavedMessageAfterDelay()
            Log.d(TAG, "👤 Username saved: '$username'")
        } else {
            _uiState.value = _uiState.value.copy(
                savedMessage = "❌ Name muss mindestens 2 Zeichen haben"
            )
            clearSavedMessageAfterDelay()
        }
    }

    // ===== Sound-Effekte =====
    fun toggleSound() {
        val newValue = !_uiState.value.isSoundEnabled
        settingsManager.setSoundEnabled(newValue)
        _uiState.value = _uiState.value.copy(isSoundEnabled = newValue)
        Log.d(TAG, "🔊 Sound toggled: $newValue")
    }

    // ===== PTT Modus =====
    fun setPttMode(mode: String) {
        settingsManager.setPttMode(mode)
        _uiState.value = _uiState.value.copy(pttMode = mode)
        Log.d(TAG, "🔘 PTT mode set: $mode")
    }

    // ===== Audio-Qualität =====
    fun setAudioQuality(sampleRate: Int) {
        settingsManager.setAudioQuality(sampleRate)
        _uiState.value = _uiState.value.copy(
            audioQuality = sampleRate,
            showRestartRequired = true
        )
        Log.d(TAG, "🎵 Audio quality set: ${sampleRate}Hz")
    }

    // ===== VAD =====
    fun toggleVad() {
        val newValue = !_uiState.value.isVadEnabled
        settingsManager.setVadEnabled(newValue)
        _uiState.value = _uiState.value.copy(isVadEnabled = newValue)
        Log.d(TAG, "🔇 VAD toggled: $newValue")
    }

    fun setVadThreshold(threshold: Int) {
        settingsManager.setVadThreshold(threshold)
        _uiState.value = _uiState.value.copy(vadThreshold = threshold)
        Log.d(TAG, "🔇 VAD threshold set: $threshold")
    }

    // ===== Server URL =====
    fun startEditingServerUrl() {
        _uiState.value = _uiState.value.copy(isServerUrlEditing = true)
    }

    fun cancelEditingServerUrl() {
        _uiState.value = _uiState.value.copy(
            isServerUrlEditing = false,
            serverUrl = settingsManager.getServerUrl()
        )
    }

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url)
    }

    fun saveServerUrl() {
        val url = _uiState.value.serverUrl.trim()
        settingsManager.setServerUrl(url)
        _uiState.value = _uiState.value.copy(
            isServerUrlEditing = false,
            savedMessage = "🌐 Server-URL gespeichert (Neustart erforderlich)"
        )
        clearSavedMessageAfterDelay()
        Log.d(TAG, "🌐 Server URL saved: '$url'")
    }

    // ===== Dark Mode =====
    fun setDarkMode(mode: String) {
        settingsManager.setDarkMode(mode)
        _uiState.value = _uiState.value.copy(darkMode = mode)
        Log.d(TAG, "🌙 Dark mode set: $mode")
    }

    // ===== Speaker Default =====
    fun toggleSpeakerDefault() {
        val newValue = !_uiState.value.isSpeakerDefault
        settingsManager.setSpeakerDefault(newValue)
        _uiState.value = _uiState.value.copy(isSpeakerDefault = newValue)
        Log.d(TAG, "🔊 Speaker default toggled: $newValue")
    }

    // ===== Audio Compression =====
    fun toggleAudioCompression() {
        val newValue = !_uiState.value.isAudioCompressionEnabled
        settingsManager.setAudioCompression(newValue)
        _uiState.value = _uiState.value.copy(isAudioCompressionEnabled = newValue)
        Log.d(TAG, "🗜️ Audio compression toggled: $newValue")
    }

    // ===== PTT Toggle Lock Threshold =====
    fun setPttToggleLockThreshold(threshold: Int) {
        settingsManager.setPttToggleLockThreshold(threshold)
        _uiState.value = _uiState.value.copy(pttToggleLockThreshold = threshold)
        Log.d(TAG, "🔒 PTT toggle lock threshold set: ${threshold}dp")
    }

    // ===== Sprache =====
    fun setLanguage(language: String) {
        settingsManager.setLanguage(language)
        _uiState.value = _uiState.value.copy(
            language = language,
            savedMessage = "🌐 Sprache geändert (Neustart erforderlich)"
        )
        clearSavedMessageAfterDelay()
        Log.d(TAG, "🌐 Language set: $language")
    }

    // ===== Reset =====
    fun showResetDialog() {
        _uiState.value = _uiState.value.copy(showResetDialog = true)
    }

    fun hideResetDialog() {
        _uiState.value = _uiState.value.copy(showResetDialog = false)
    }

    fun resetAllSettings() {
        settingsManager.resetAllSettings()
        loadSettings()
        _uiState.value = _uiState.value.copy(
            showResetDialog = false,
            savedMessage = "🔄 Alle Einstellungen zurückgesetzt"
        )
        clearSavedMessageAfterDelay()
        Log.d(TAG, "🔄 All settings reset")
    }

    fun dismissRestartRequired() {
        _uiState.value = _uiState.value.copy(showRestartRequired = false)
    }

    fun clearSavedMessage() {
        _uiState.value = _uiState.value.copy(savedMessage = null)
    }

    private fun clearSavedMessageAfterDelay() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.value = _uiState.value.copy(savedMessage = null)
        }
    }
}
