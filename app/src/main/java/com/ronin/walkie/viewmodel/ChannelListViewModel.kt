package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ronin.walkie.model.Channel
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChannelListUiState(
    val channels: List<Channel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentUsername: String = "",
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false
)

class ChannelListViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChannelListVM"
        private const val LOAD_TIMEOUT_MS = 10000L
    }

    private val gson = Gson()
    private val _uiState = MutableStateFlow(ChannelListUiState())
    val uiState: StateFlow<ChannelListUiState> = _uiState.asStateFlow()

    private var loadTimeoutJob: Job? = null

    init {
        Log.d(TAG, "🏗️ ChannelListViewModel created")

        // SavedState wiederherstellen
        savedStateHandle.get<String>("saved_username")?.let { savedUsername ->
            if (savedUsername.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(currentUsername = savedUsername)
                Log.d(TAG, "   Restored username from SavedStateHandle: '$savedUsername'")
            }
        }

        observeMessages()
    }

    private fun observeMessages() {
        Log.d(TAG, "👂 Starting to observe WebSocket messages")
        viewModelScope.launch {
            webSocketClient.messages.collect { message ->
                Log.d(TAG, "📨 ChannelListViewModel received message: type='${message.type}'")
                handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: ServerMessage) {
        Log.d(TAG, "⚙️ handleMessage: type='${message.type}', payload=${message.payload}")
        when (message.type) {
            "connected" -> {
                Log.d(TAG, "✅ WebSocket connected!")
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    isReconnecting = false,
                    error = null
                )
                // Automatisch Channels laden wenn verbunden
                val currentUsername = _uiState.value.currentUsername
                if (currentUsername.isNotEmpty()) {
                    Log.d(TAG, "   User was logged in, re-logging in after reconnect...")
                    webSocketClient.login(currentUsername)
                } else {
                    Log.d(TAG, "   Not logged in yet, just loading channels")
                    loadChannels()
                }
            }
            "disconnected" -> {
                Log.d(TAG, "🔌 WebSocket disconnected!")
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isReconnecting = webSocketClient.isConnecting()
                )
            }
            "login_success" -> {
                Log.d(TAG, "✅ Re-login successful after reconnect! Loading channels...")
                loadChannels()
            }
            "channel_list" -> {
                Log.d(TAG, "📋 Received channel_list")
                loadTimeoutJob?.cancel()
                val channelsJson = gson.toJson(message.payload?.get("channels"))
                Log.d(TAG, "   channels JSON: $channelsJson")
                val type = object : TypeToken<List<Channel>>() {}.type
                val channels: List<Channel> = gson.fromJson(channelsJson, type)
                Log.d(TAG, "   Parsed ${channels.size} channels")
                _uiState.value = _uiState.value.copy(
                    channels = channels,
                    isLoading = false
                )
            }
            "channel_created" -> {
                Log.d(TAG, "📢 Received channel_created, reloading channels")
                forceLoadChannels()
            }
            "channel_deleted" -> {
                Log.d(TAG, "🗑️ Received channel_deleted, reloading channels")
                forceLoadChannels()
            }
            "join_channel_error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Wrong password"
                Log.e(TAG, "❌ Join channel error: $errorMsg")
                _uiState.value = _uiState.value.copy(
                    error = errorMsg
                )
            }
            "error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Ein Fehler ist aufgetreten"
                Log.e(TAG, "❌ Error: $errorMsg")
                loadTimeoutJob?.cancel()
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

    private fun forceLoadChannels() {
        Log.d(TAG, "📋 forceLoadChannels() called")
        webSocketClient.getChannels()
    }

    fun loadChannels() {
        Log.d(TAG, "📋 loadChannels() called")
        Log.d(TAG, "   isConnected=${webSocketClient.isConnected}")
        Log.d(TAG, "   isOpen=${webSocketClient.isOpen}")

        if (_uiState.value.isLoading) {
            Log.d(TAG, "   Already loading, skipping")
            return
        }

        if (!webSocketClient.isConnected) {
            Log.w(TAG, "   Not connected, cannot load channels")
            _uiState.value = _uiState.value.copy(
                error = "Keine Verbindung zum Server"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)
        Log.d(TAG, "   Calling webSocketClient.getChannels()")
        webSocketClient.getChannels()

        // Timeout für Channel-Load
        loadTimeoutJob?.cancel()
        loadTimeoutJob = viewModelScope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (_uiState.value.isLoading) {
                Log.w(TAG, "⏰ Channel load timeout after ${LOAD_TIMEOUT_MS}ms")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Timeout beim Laden der Channels. Server antwortet nicht."
                )
            }
        }
    }

    fun createChannel(name: String, description: String = "", color: String = "#4CAF50", password: String = "") {
        Log.d(TAG, "📢 createChannel: name='$name', hasPassword=${password.isNotEmpty()}")
        if (name.length < 2) {
            Log.w(TAG, "   Channel name too short")
            _uiState.value = _uiState.value.copy(
                error = "Channel-Name muss mindestens 2 Zeichen haben"
            )
            return
        }

        if (!webSocketClient.isConnected) {
            Log.w(TAG, "   Not connected, cannot create channel")
            _uiState.value = _uiState.value.copy(
                error = "Keine Verbindung zum Server"
            )
            return
        }

        webSocketClient.createChannel(name, description, color, password)
    }

    fun deleteChannel(channelId: Int) {
        Log.d(TAG, "🗑️ deleteChannel($channelId)")
        if (!webSocketClient.isConnected) {
            Log.w(TAG, "   Not connected, cannot delete channel")
            _uiState.value = _uiState.value.copy(
                error = "Keine Verbindung zum Server"
            )
            return
        }
        webSocketClient.deleteChannel(channelId)
    }

    fun joinChannel(channelId: Int, password: String = "") {
        Log.d(TAG, "🚪 joinChannel($channelId, hasPassword=${password.isNotEmpty()})")
        if (!webSocketClient.isConnected) {
            Log.w(TAG, "   Not connected, cannot join channel")
            _uiState.value = _uiState.value.copy(
                error = "Keine Verbindung zum Server"
            )
            return
        }
        webSocketClient.joinChannel(channelId, password)
    }

    fun setUsername(username: String) {
        Log.d(TAG, "👤 setUsername('$username')")
        _uiState.value = _uiState.value.copy(currentUsername = username)
        savedStateHandle["saved_username"] = username
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 ChannelListViewModel.onCleared()")
        loadTimeoutJob?.cancel()
    }
}
