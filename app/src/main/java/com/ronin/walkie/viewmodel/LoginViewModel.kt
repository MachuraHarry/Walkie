package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val error: String? = null,
    val isConnected: Boolean = false
)

class LoginViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LoginVM"
    }

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "🏗️ LoginViewModel created")
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
                _uiState.value = _uiState.value.copy(isConnected = true)
            }
            "disconnected" -> {
                Log.d(TAG, "🔌 WebSocket disconnected!")
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isLoggedIn = false,
                    isLoading = false
                )
            }
            "login_success" -> {
                Log.d(TAG, "✅✅✅ Login successful!")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    error = null
                )
            }
            "login_error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Login fehlgeschlagen"
                Log.e(TAG, "❌ Login error: $errorMsg")
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

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            username = username,
            error = null
        )

        Log.d(TAG, "   Calling webSocketClient.login('$username')")
        webSocketClient.login(username)
    }

    fun connect(serverUrl: String) {
        Log.d(TAG, "🔌 connect('$serverUrl') called")
        Log.d(TAG, "   isConnected=${webSocketClient.isConnected}")
        if (!webSocketClient.isConnected) {
            Log.d(TAG, "   Calling webSocketClient.connect()")
            webSocketClient.connect()
        } else {
            Log.d(TAG, "   Already connected")
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 LoginViewModel.onCleared()")
        if (webSocketClient.isConnected) {
            webSocketClient.close()
        }
    }
}
