package com.ronin.walkie.viewmodel

import android.app.Application
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

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        observeMessages()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            webSocketClient.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: ServerMessage) {
        when (message.type) {
            "connected" -> {
                _uiState.value = _uiState.value.copy(isConnected = true)
            }
            "disconnected" -> {
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isLoggedIn = false,
                    isLoading = false
                )
            }
            "login_success" -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    error = null
                )
            }
            "login_error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Login fehlgeschlagen"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorMsg
                )
            }
            "error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Ein Fehler ist aufgetreten"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorMsg
                )
            }
        }
    }

    fun login(username: String) {
        if (username.length < 2) {
            _uiState.value = _uiState.value.copy(
                error = "Name muss mindestens 2 Zeichen haben"
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            username = username,
            error = null
        )

        webSocketClient.login(username)
    }

    fun connect(serverUrl: String) {
        if (!webSocketClient.isConnected) {
            webSocketClient.connect()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        if (webSocketClient.isConnected) {
            webSocketClient.close()
        }
    }
}
