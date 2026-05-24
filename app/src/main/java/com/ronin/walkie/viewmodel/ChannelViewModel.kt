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
    val ping: Long = 0,
    val error: String? = null,
    val isSpeakerOn: Boolean = true
)

class ChannelViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val username: String
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChannelVM"
    }

    private val _uiState = MutableStateFlow(TalkUiState())
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    private var hasJoined = false
    private var messagesJob: kotlinx.coroutines.Job? = null

    init {
        Log.d(TAG, "🏗️ ChannelViewModel created for user '$username'")
        // AudioPlayer mit WebSocket verbinden
        audioPlayer.connectToWebSocket(webSocketClient)
        Log.d(TAG, "   AudioPlayer connected to WebSocket")
    }

    private fun observeMessages() {
        Log.d(TAG, "👂 Starting to observe WebSocket messages")
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            webSocketClient.messages.collect { message ->
                Log.d(TAG, "📨 ChannelViewModel received message: type='${message.type}'")
                handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: ServerMessage) {
        Log.d(TAG, "⚙️ handleMessage: type='${message.type}', payload=${message.payload}")
        when (message.type) {
            "user_list" -> {
                val users = (message.payload?.get("users") as? List<*>)?.map { it.toString() } ?: emptyList()
                Log.d(TAG, "👥 User list received: $users")
                _uiState.value = _uiState.value.copy(users = users)
            }
            "user_joined" -> {
                val users = (message.payload?.get("users") as? List<*>)?.map { it.toString() } ?: emptyList()
                val joinedUser = message.payload?.get("username") as? String ?: "unknown"
                Log.d(TAG, "👤 User joined: '$joinedUser', users now: $users")
                _uiState.value = _uiState.value.copy(users = users)
            }
            "user_left" -> {
                val leftUser = message.payload?.get("username") as? String ?: return
                Log.d(TAG, "👋 User left: '$leftUser'")
                _uiState.value = _uiState.value.copy(
                    users = _uiState.value.users - leftUser,
                    talkingUsers = _uiState.value.talkingUsers - leftUser
                )
            }
            "user_talking" -> {
                val talkingUser = message.payload?.get("username") as? String ?: return
                Log.d(TAG, "🔴 User talking: '$talkingUser'")
                _uiState.value = _uiState.value.copy(
                    talkingUsers = _uiState.value.talkingUsers + talkingUser
                )
            }
            "user_stopped_talking" -> {
                val stoppedUser = message.payload?.get("username") as? String ?: return
                Log.d(TAG, "🟢 User stopped talking: '$stoppedUser'")
                _uiState.value = _uiState.value.copy(
                    talkingUsers = _uiState.value.talkingUsers - stoppedUser
                )
            }
            "connected" -> {
                Log.d(TAG, "✅ WebSocket connected!")
                _uiState.value = _uiState.value.copy(isConnected = true)
            }
            "disconnected" -> {
                Log.d(TAG, "🔌 WebSocket disconnected!")
                _uiState.value = _uiState.value.copy(isConnected = false)
            }
            else -> {
                Log.d(TAG, "ℹ️ Unhandled message type: '${message.type}'")
            }
        }
    }

    fun joinChannel(channel: Channel) {
        Log.d(TAG, "🚪 joinChannel(${channel.id}, '${channel.name}') called")
        Log.d(TAG, "   hasJoined=$hasJoined")
        Log.d(TAG, "   isConnected=${webSocketClient.isConnected}")
        Log.d(TAG, "   isOpen=${webSocketClient.isOpen}")
        
        _uiState.value = _uiState.value.copy(channel = channel)
        hasJoined = true

        // AudioRecorder für diesen Channel konfigurieren
        Log.d(TAG, "   Setting AudioRecorder channelId=${channel.id}")
        audioRecorder.setChannelId(channel.id)

        // AudioPlayer starten
        Log.d(TAG, "   Starting AudioPlayer playback")
        audioPlayer.startPlayback()

        Log.d(TAG, "   Calling webSocketClient.joinChannel(${channel.id})")
        webSocketClient.joinChannel(channel.id)
        observeMessages()
        Log.d(TAG, "   joinChannel complete")
    }

    fun leaveChannel() {
        Log.d(TAG, "🚪 leaveChannel() called")
        hasJoined = false
        messagesJob?.cancel()
        messagesJob = null

        // Aufnahme stoppen falls aktiv
        if (audioRecorder.isRecording()) {
            Log.d(TAG, "   Stopping recording")
            audioRecorder.stopRecording()
        }

        val channelId = _uiState.value.channel?.id ?: return
        Log.d(TAG, "   Calling webSocketClient.leaveChannel($channelId)")
        webSocketClient.leaveChannel(channelId)

        // AudioPlayer stoppen
        Log.d(TAG, "   Stopping AudioPlayer playback")
        audioPlayer.stopPlayback()

        _uiState.value = TalkUiState()
        Log.d(TAG, "   leaveChannel complete")
    }

    fun startTransmitting() {
        val channelId = _uiState.value.channel?.id ?: return
        Log.d(TAG, "🔴 startTransmitting() in channel $channelId")
        Log.d(TAG, "   isRecording=${audioRecorder.isRecording()}")
        
        _uiState.value = _uiState.value.copy(isTransmitting = true)

        // Mikrofon-Aufnahme starten -> Audio wird automatisch per WebSocket gesendet
        Log.d(TAG, "   Starting AudioRecorder...")
        if (audioRecorder.startRecording()) {
            Log.d(TAG, "   Calling webSocketClient.startTalking($channelId)")
            webSocketClient.startTalking(channelId)
            Log.d(TAG, "✅ Started transmitting in channel $channelId")
        } else {
            Log.e(TAG, "❌ Failed to start recording!")
            _uiState.value = _uiState.value.copy(isTransmitting = false, error = "Failed to start recording")
        }
    }

    fun stopTransmitting() {
        val channelId = _uiState.value.channel?.id ?: return
        Log.d(TAG, "🟢 stopTransmitting() in channel $channelId")
        
        _uiState.value = _uiState.value.copy(isTransmitting = false)

        Log.d(TAG, "   Stopping AudioRecorder...")
        audioRecorder.stopRecording()
        Log.d(TAG, "   Calling webSocketClient.stopTalking($channelId)")
        webSocketClient.stopTalking(channelId)
        Log.d(TAG, "✅ Stopped transmitting in channel $channelId")
    }

    fun toggleTransmitting() {
        val currentState = _uiState.value
        Log.d(TAG, "🔄 toggleTransmitting() - current isToggleMode=${currentState.isToggleMode}")
        
        if (currentState.isToggleMode) {
            // Toggle ausschalten
            Log.d(TAG, "   Turning toggle OFF")
            _uiState.value = currentState.copy(isToggleMode = false, isTransmitting = false)
            audioRecorder.stopRecording()
            val channelId = _uiState.value.channel?.id ?: return
            webSocketClient.stopTalking(channelId)
        } else {
            // Toggle einschalten
            Log.d(TAG, "   Turning toggle ON")
            _uiState.value = currentState.copy(isToggleMode = true, isTransmitting = true)
            val channelId = _uiState.value.channel?.id ?: return
            if (audioRecorder.startRecording()) {
                webSocketClient.startTalking(channelId)
            }
        }
    }

    fun toggleSpeaker() {
        val currentState = _uiState.value
        val newSpeakerState = !currentState.isSpeakerOn
        Log.d(TAG, "🔊 toggleSpeaker() - new isSpeakerOn=$newSpeakerState")
        
        _uiState.value = currentState.copy(isSpeakerOn = newSpeakerState)
        
        if (newSpeakerState) {
            audioPlayer.startPlayback()
        } else {
            audioPlayer.stopPlayback()
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 ChannelViewModel.onCleared()")
        messagesJob?.cancel()
        audioRecorder.stopRecording()
        audioPlayer.stopPlayback()
    }
}
