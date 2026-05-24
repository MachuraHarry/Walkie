package com.ronin.walkie.viewmodel

import android.app.Application
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

    private val gson = Gson()
    private val _uiState = MutableStateFlow(ChannelListUiState())
    val uiState: StateFlow<ChannelListUiState> = _uiState.asStateFlow()

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
            "channel_list" -> {
                val channelsJson = gson.toJson(message.payload?.get("channels"))
                val type = object : TypeToken<List<Channel>>() {}.type
                val channels: List<Channel> = gson.fromJson(channelsJson, type)
                _uiState.value = _uiState.value.copy(
                    channels = channels,
                    isLoading = false
                )
            }
            "channel_created" -> {
                // Channel-Liste neu laden
                loadChannels()
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

    fun loadChannels() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        webSocketClient.getChannels()
    }

    fun createChannel(name: String, description: String = "", color: String = "#4CAF50") {
        if (name.length < 2) {
            _uiState.value = _uiState.value.copy(
                error = "Channel-Name muss mindestens 2 Zeichen haben"
            )
            return
        }
        webSocketClient.createChannel(name, description, color)
    }

    fun joinChannel(channelId: Int) {
        webSocketClient.joinChannel(channelId)
    }

    fun setUsername(username: String) {
        _uiState.value = _uiState.value.copy(currentUsername = username)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
