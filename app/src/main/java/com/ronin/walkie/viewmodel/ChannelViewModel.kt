package com.ronin.walkie.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.walkie.model.Channel
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.model.TalkingStatus
import com.ronin.walkie.network.SignalingClient
import com.ronin.walkie.network.WalkieWebSocketClient
import com.ronin.walkie.webrtc.WebRTCManager
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
    val error: String? = null
)

class ChannelViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient,
    private val signalingClient: SignalingClient,
    private val webRTCManager: WebRTCManager,
    private val username: String
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TalkUiState())
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    init {
        observeMessages()
        setupWebRTCCallbacks()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            webSocketClient.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private fun setupWebRTCCallbacks() {
        webRTCManager.onRemoteAudioStarted = { peerId ->
            _uiState.value = _uiState.value.copy(
                talkingUsers = _uiState.value.talkingUsers + peerId
            )
        }
        webRTCManager.onRemoteAudioStopped = { peerId ->
            _uiState.value = _uiState.value.copy(
                talkingUsers = _uiState.value.talkingUsers - peerId
            )
        }
    }

    private fun handleMessage(message: ServerMessage) {
        when (message.type) {
            "user_list" -> {
                val users = (message.payload?.get("users") as? List<*>)?.map { it.toString() } ?: emptyList()
                _uiState.value = _uiState.value.copy(users = users)

                // WebRTC-Verbindungen zu allen anderen Nutzern aufbauen
                val channelId = _uiState.value.channel?.id ?: return
                for (user in users) {
                    if (user != username && !isPeerConnected(user)) {
                        webRTCManager.createPeerConnection(user, channelId)
                        webRTCManager.createOffer(user, channelId)
                    }
                }
            }
            "user_joined" -> {
                val joinedUser = message.payload?.get("username") as? String ?: return
                val users = (message.payload?.get("users") as? List<*>)?.map { it.toString() } ?: emptyList()
                _uiState.value = _uiState.value.copy(users = users)

                // WebRTC-Verbindung zum neuen Nutzer aufbauen
                val channelId = _uiState.value.channel?.id ?: return
                if (joinedUser != username) {
                    webRTCManager.createPeerConnection(joinedUser, channelId)
                    webRTCManager.createOffer(joinedUser, channelId)
                }
            }
            "user_left" -> {
                val leftUser = message.payload?.get("username") as? String ?: return
                _uiState.value = _uiState.value.copy(
                    users = _uiState.value.users - leftUser,
                    talkingUsers = _uiState.value.talkingUsers - leftUser
                )
                webRTCManager.disconnectPeer(leftUser)
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
            "signal" -> {
                handleSignal(message.payload)
            }
            "connected" -> {
                _uiState.value = _uiState.value.copy(isConnected = true)
            }
            "disconnected" -> {
                _uiState.value = _uiState.value.copy(isConnected = false)
            }
            "error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Ein Fehler ist aufgetreten"
                _uiState.value = _uiState.value.copy(error = errorMsg)
            }
        }
    }

    private fun handleSignal(payload: Map<String, Any>?) {
        if (payload == null) return

        val signal = signalingClient.parseSignal(payload) ?: return

        when (signal.type) {
            "offer" -> {
                val offerData = signal.data as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                webRTCManager.handleOffer(offerData as? Map<String, Any> ?: return, signal.from, signal.channelId)
            }
            "answer" -> {
                val answerData = signal.data as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                webRTCManager.handleAnswer(answerData as? Map<String, Any> ?: return, signal.from)
            }
            "ice_candidate" -> {
                val candidateData = signal.data as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                webRTCManager.handleIceCandidate(candidateData as? Map<String, Any> ?: return, signal.from)
            }
        }
    }

    fun joinChannel(channel: Channel) {
        _uiState.value = _uiState.value.copy(channel = channel)
        webSocketClient.joinChannel(channel.id)
        webRTCManager.initialize()
    }

    fun leaveChannel() {
        val channelId = _uiState.value.channel?.id ?: return
        webSocketClient.leaveChannel(channelId)
        webRTCManager.disconnectAll()
        _uiState.value = TalkUiState()
    }

    fun startTransmitting() {
        val channelId = _uiState.value.channel?.id ?: return
        _uiState.value = _uiState.value.copy(isTransmitting = true)
        webSocketClient.startTalking(channelId)
        webRTCManager.setAudioEnabled(true)
    }

    fun stopTransmitting() {
        val channelId = _uiState.value.channel?.id ?: return
        _uiState.value = _uiState.value.copy(isTransmitting = false)
        webSocketClient.stopTalking(channelId)
        webRTCManager.setAudioEnabled(false)
    }

    fun toggleTransmitting() {
        val currentState = _uiState.value
        if (currentState.isToggleMode) {
            // Toggle ausschalten
            _uiState.value = currentState.copy(isToggleMode = false, isTransmitting = false)
            webRTCManager.setAudioEnabled(false)
            val channelId = _uiState.value.channel?.id ?: return
            webSocketClient.stopTalking(channelId)
        } else {
            // Toggle einschalten
            _uiState.value = currentState.copy(isToggleMode = true, isTransmitting = true)
            webRTCManager.setAudioEnabled(true)
            val channelId = _uiState.value.channel?.id ?: return
            webSocketClient.startTalking(channelId)
        }
    }

    private fun isPeerConnected(peerId: String): Boolean {
        return false // Vereinfacht: Prüfung ob PeerConnection existiert
    }

    override fun onCleared() {
        super.onCleared()
        webRTCManager.disconnectAll()
    }
}
