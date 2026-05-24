package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ronin.walkie.model.Channel
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChannelListUiState(
    val channels: List<Channel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentUsername: String = ""
)

class ChannelListViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChannelListVM"
    }

    private val gson = Gson()
    private val _uiState = MutableStateFlow(ChannelListUiState())
    val uiState: StateFlow<ChannelListUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "🏗️ ChannelListViewModel created")
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
            "channel_list" -> {
                Log.d(TAG, "📋 Received channel_list")
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
                // Fallback: Server sendet manchmal channel_created (alte Version)
                // In dem Fall einfach die Channel-Liste neu laden
                Log.d(TAG, "📢 Received channel_created, reloading channels")
                forceLoadChannels()
            }
            "error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Ein Fehler ist aufgetreten"
                Log.e(TAG, "❌ Error: $errorMsg")
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

    /**
     * Lädt die Channel-Liste ohne isLoading-Sperre.
     * Wird für Broadcast-Nachrichten verwendet, die von außen kommen.
     */
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
        _uiState.value = _uiState.value.copy(isLoading = true)
        Log.d(TAG, "   Calling webSocketClient.getChannels()")
        webSocketClient.getChannels()
    }

    fun createChannel(name: String, description: String = "", color: String = "#4CAF50") {
        Log.d(TAG, "📢 createChannel: name='$name'")
        if (name.length < 2) {
            Log.w(TAG, "   Channel name too short")
            _uiState.value = _uiState.value.copy(
                error = "Channel-Name muss mindestens 2 Zeichen haben"
            )
            return
        }
        webSocketClient.createChannel(name, description, color)
    }

    fun joinChannel(channelId: Int) {
        Log.d(TAG, "🚪 joinChannel($channelId)")
        webSocketClient.joinChannel(channelId)
    }

    fun setUsername(username: String) {
        Log.d(TAG, "👤 setUsername('$username')")
        _uiState.value = _uiState.value.copy(currentUsername = username)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
