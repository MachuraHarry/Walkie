package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.walkie.audio.AudioPlayer
import com.ronin.walkie.audio.AudioRecorder
import com.ronin.walkie.model.Channel
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TalkUiState(
    val channel: Channel? = null,
    val users: List<String> = emptyList(),
    val talkingUsers: Set<String> = emptySet(),
    val isTransmitting: Boolean = false,
    val isToggleMode: Boolean = false,
    val isConnected: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val ping: Long = 0,
    val error: String? = null
)

class ChannelViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val username: String
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChannelViewModel"
    }

    private val _uiState = MutableStateFlow(TalkUiState())
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

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
            "user_list" -> {
                val users = (message.payload?.get("users") as? List<*>)?.map { it.toString() } ?: emptyList()
                _uiState.value = _uiState.value.copy(users = users)
            }
            "user_joined" -> {
                val users = (message.payload?.get("users") as? List<*>)?.map { it.toString() } ?: emptyList()
                _uiState.value = _uiState.value.copy(users = users)
            }
            "user_left" -> {
                val leftUser = message.payload?.get("username") as? String ?: return
                _uiState.value = _uiState.value.copy(
                    users = _uiState.value.users - leftUser,
                    talkingUsers = _uiState.value.talkingUsers - leftUser
                )
            }
            "user_talking" -> {
                val talkingUser = message.payload?.get("username") as? String ?: return
                _uiState.value = _uiState.value.copy(
                    talkingUsers = _uiState.value.talkingUsers + talkingUser
                )
            }
            "user_stopped_talking" -> {
                val stoppedUser = message.payload?.get("username") as? String ?: return
                _uiState.value = _uiState.value.copy(
                    talkingUsers = _uiState.value.talkingUsers - stoppedUser
                )
            }
            "connected" -> {
                _uiState.value = _uiState.value.copy(isConnected = true)
            }
            "disconnected" -> {
                _uiState.value = _uiState.value.copy(isConnected = false)
            }
        }
    }

    fun joinChannel(channel: Channel) {
        _uiState.value = _uiState.value.copy(channel = channel)
        audioRecorder.setChannelId(channel.id)
        audioPlayer.connectToWebSocket(webSocketClient)
        audioPlayer.startPlayback()
        webSocketClient.joinChannel(channel.id)
    }

    fun leaveChannel() {
        val channelId = _uiState.value.channel?.id ?: return
        audioRecorder.stopRecording()
        audioPlayer.stopPlayback()
        webSocketClient.leaveChannel(channelId)
        _uiState.value = TalkUiState()
    }

    fun startTransmitting() {
        val channelId = _uiState.value.channel?.id ?: return
        _uiState.value = _uiState.value.copy(isTransmitting = true)
        webSocketClient.startTalking(channelId)
        audioRecorder.startRecording()
    }

    fun stopTransmitting() {
        val channelId = _uiState.value.channel?.id ?: return
        _uiState.value = _uiState.value.copy(isTransmitting = false)
        webSocketClient.stopTalking(channelId)
        audioRecorder.stopRecording()
    }

    fun toggleTransmitting() {
        val currentState = _uiState.value
        if (currentState.isToggleMode) {
            // Toggle ausschalten
            _uiState.value = currentState.copy(isToggleMode = false, isTransmitting = false)
            audioRecorder.stopRecording()
            val channelId = _uiState.value.channel?.id ?: return
            webSocketClient.stopTalking(channelId)
        } else {
            // Toggle einschalten
            _uiState.value = currentState.copy(isToggleMode = true, isTransmitting = true)
            audioRecorder.startRecording()
            val channelId = _uiState.value.channel?.id ?: return
            webSocketClient.startTalking(channelId)
        }
    }

    fun toggleSpeaker() {
        val currentState = _uiState.value
        val newSpeakerState = !currentState.isSpeakerOn
        _uiState.value = currentState.copy(isSpeakerOn = newSpeakerState)
        Log.d(TAG, "🔊 toggleSpeaker: ${if (newSpeakerState) "ON" else "OFF"}")
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.stopPlayback()
    }
}
