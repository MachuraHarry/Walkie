package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val error: String? = null,
    val isConnected: Boolean = false,
    val connectionAttempts: Int = 0,
    val isReconnecting: Boolean = false
)

class LoginViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LoginVM"
        private const val LOGIN_TIMEOUT_MS = 10000L
        private const val MAX_CONNECTION_ATTEMPTS = 3
    }

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var loginTimeoutJob: Job? = null
    private var connectionAttempts = 0

    init {
        Log.d(TAG, "🏗️ LoginViewModel created")

        // SavedState wiederherstellen
        savedStateHandle.get<String>("saved_username")?.let { savedUsername ->
            if (savedUsername.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(username = savedUsername)
                Log.d(TAG, "   Restored username from SavedStateHandle: '$savedUsername'")
            }
        }

        observeMessages()
    }

    private fun observeMessages() {
        Log.d(TAG, "👂 Starting to observe WebSocket messages")
        viewModelScope.launch {
            webSocketClient.messages.collect { message ->
                Log.d(TAG, "📨 LoginViewModel received message: type='${message.type}'")
                handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: ServerMessage) {
        Log.d(TAG, "⚙️ handleMessage: type='${message.type}', payload=${message.payload}")
        when (message.type) {
            "connected" -> {
                Log.d(TAG, "✅ WebSocket connected!")
                connectionAttempts = 0
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    isReconnecting = false,
                    error = null
                )
            }
            "disconnected" -> {
                Log.d(TAG, "🔌 WebSocket disconnected!")
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isLoggedIn = false,
                    isLoading = false,
                    isReconnecting = webSocketClient.isConnecting()
                )
            }
            "login_success" -> {
                Log.d(TAG, "✅✅✅ Login successful!")
                loginTimeoutJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    error = null
                )
                // Username speichern
                savedStateHandle["saved_username"] = _uiState.value.username
            }
            "login_error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Login fehlgeschlagen"
                Log.e(TAG, "❌ Login error: $errorMsg")
                loginTimeoutJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorMsg
                )
            }
            "error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Ein Fehler ist aufgetreten"
                Log.e(TAG, "❌ Server error: $errorMsg")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorMsg
                )
            }
            else -> {
                Log.d(TAG, "ℹ️ Unhandled message type: '${message.type}'")
            }
        }
    }

    fun login(username: String) {
        Log.d(TAG, "🔑 login('$username') called")
        Log.d(TAG, "   isConnected=${webSocketClient.isConnected}")
        Log.d(TAG, "   isOpen=${webSocketClient.isOpen}")
        Log.d(TAG, "   readyState=${webSocketClient.readyState}")

        if (username.length < 2) {
            Log.w(TAG, "   Username too short")
            _uiState.value = _uiState.value.copy(
                error = "Name muss mindestens 2 Zeichen haben"
            )
            return
        }

        // Verhindere mehrfache Login-Versuche
        if (_uiState.value.isLoading) {
            Log.d(TAG, "   Already loading, skipping")
            return
        }

        // Prüfen ob verbunden
        if (!webSocketClient.isConnected) {
            Log.w(TAG, "   Not connected, attempting to connect first...")
            connect(webSocketClient.uri.toString())
            _uiState.value = _uiState.value.copy(
                error = "Keine Verbindung zum Server. Versuche zu verbinden..."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            username = username,
            error = null
        )

        Log.d(TAG, "   Calling webSocketClient.login('$username')")
        webSocketClient.login(username)

        // Login-Timeout: Wenn nach 10s keine Antwort, abbrechen
        loginTimeoutJob?.cancel()
        loginTimeoutJob = viewModelScope.launch {
            delay(LOGIN_TIMEOUT_MS)
            if (_uiState.value.isLoading) {
                Log.w(TAG, "⏰ Login timeout after ${LOGIN_TIMEOUT_MS}ms")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Login-Timeout. Server antwortet nicht."
                )
            }
        }
    }

    fun connect(serverUrl: String) {
        Log.d(TAG, "🔌 connect('$serverUrl') called")
        Log.d(TAG, "   isConnected=${webSocketClient.isConnected}")

        if (connectionAttempts >= MAX_CONNECTION_ATTEMPTS) {
            Log.e(TAG, "❌ Max connection attempts ($MAX_CONNECTION_ATTEMPTS) reached")
            _uiState.value = _uiState.value.copy(
                error = "Server nicht erreichbar nach $MAX_CONNECTION_ATTEMPTS Versuchen."
            )
            return
        }

        if (!webSocketClient.isConnected && !webSocketClient.isConnecting()) {
            connectionAttempts++
            Log.d(TAG, "   Connection attempt $connectionAttempts/$MAX_CONNECTION_ATTEMPTS")
            Log.d(TAG, "   Calling webSocketClient.connect()")
            webSocketClient.connect()
        } else {
            Log.d(TAG, "   Already connected or connecting")
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 LoginViewModel.onCleared()")
        loginTimeoutJob?.cancel()
    }
}
