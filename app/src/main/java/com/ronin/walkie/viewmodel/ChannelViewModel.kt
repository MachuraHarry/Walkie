package com.ronin.walkie.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ronin.walkie.WalkieApplication
import com.ronin.walkie.audio.AudioPlayer
import com.ronin.walkie.audio.AudioRecorder
import com.ronin.walkie.audio.SoundEffectPlayer
import com.ronin.walkie.model.Channel
import com.ronin.walkie.model.ServerMessage
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TalkUiState(
    val channel: Channel? = null,
    val users: List<String> = emptyList(),
    val talkingUsers: Set<String> = emptySet(),
    val isTransmitting: Boolean = false,
    val isToggleMode: Boolean = false,
    val isConnected: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val isHeadsetPlugged: Boolean = false,
    val ping: Long = 0,
    val error: String? = null,
    val isReconnecting: Boolean = false,
    val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN
)

enum class ConnectionQuality {
    UNKNOWN,
    GOOD,
    FAIR,
    POOR,
    DISCONNECTED
}

class ChannelViewModel(
    application: Application,
    private val webSocketClient: WalkieWebSocketClient,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val soundEffectPlayer: SoundEffectPlayer,
    private val username: String,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChannelViewModel"
        private const val PING_INTERVAL_MS = 5000L
        private const val PING_TIMEOUT_MS = 3000L
        private const val RECONNECT_CHECK_INTERVAL_MS = 2000L
    }

    private val _uiState = MutableStateFlow(TalkUiState())
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    private var pingJob: Job? = null
    private var lastPingTime = 0L
    private var missedPongs = 0

    init {
        Log.d(TAG, "🏗️ ChannelViewModel created for user '$username'")

        // SavedState wiederherstellen
        savedStateHandle.get<Int>("saved_channel_id")?.let { savedChannelId ->
            savedStateHandle.get<String>("saved_channel_name")?.let { savedChannelName ->
                if (savedChannelId > 0) {
                    _uiState.value = _uiState.value.copy(
                        channel = Channel(id = savedChannelId, name = savedChannelName)
                    )
                    Log.d(TAG, "   Restored channel from SavedStateHandle: #$savedChannelId '$savedChannelName'")
                }
            }
        }

        observeMessages()
        startPingMonitor()
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
                // ON-Sound abspielen, wenn ein ANDERER User zu sprechen beginnt
                // (der Sender selbst spielt den Sound bereits in startTransmitting())
                if (talkingUser != username) {
                    soundEffectPlayer.playOnSound()
                }
            }
            "user_stopped_talking" -> {
                val stoppedUser = message.payload?.get("username") as? String ?: return
                _uiState.value = _uiState.value.copy(
                    talkingUsers = _uiState.value.talkingUsers - stoppedUser
                )
                // OFF-Sound abspielen, wenn ein ANDERER User aufhört zu sprechen
                // (der Sender selbst spielt den Sound bereits in stopTransmitting())
                if (stoppedUser != username) {
                    soundEffectPlayer.playOffSound()
                }
            }
            "connected" -> {
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    isReconnecting = false,
                    connectionQuality = ConnectionQuality.GOOD,
                    error = null
                )
                missedPongs = 0

                // Nach einem Reconnect: Channel automatisch wieder joinen
                val currentChannel = _uiState.value.channel
                if (currentChannel != null) {
                    Log.d(TAG, "🔄 Reconnected! Re-joining channel #${currentChannel.id} '${currentChannel.name}'")
                    webSocketClient.joinChannel(currentChannel.id)
                    // Audio-Playback neu starten falls nötig
                    if (!audioPlayer.isPlaying()) {
                        audioPlayer.startPlayback()
                    }
                }
            }
            "disconnected" -> {
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isReconnecting = webSocketClient.isConnecting(),
                    connectionQuality = ConnectionQuality.DISCONNECTED
                )
            }
            "pong" -> {
                // Ping-Antwort vom Server
                if (lastPingTime > 0) {
                    val rtt = System.currentTimeMillis() - lastPingTime
                    _uiState.value = _uiState.value.copy(ping = rtt)

                    // Verbindungsqualität basierend auf RTT
                    val quality = when {
                        rtt < 100 -> ConnectionQuality.GOOD
                        rtt < 300 -> ConnectionQuality.FAIR
                        else -> ConnectionQuality.POOR
                    }
                    _uiState.value = _uiState.value.copy(connectionQuality = quality)
                    missedPongs = 0
                }
            }
            "error" -> {
                val errorMsg = message.payload?.get("message") as? String ?: "Ein Fehler ist aufgetreten"
                Log.e(TAG, "❌ Error: $errorMsg")
                _uiState.value = _uiState.value.copy(error = errorMsg)
            }
        }
    }

    /**
     * Überwacht regelmäßig die Verbindungsqualität und den Headset-Status.
     * Der Headset-Check dient als Fallback, falls der BroadcastReceiver
     * Ereignisse wie Bluetooth-Wiederverbindung nicht zuverlässig empfängt.
     */
    private fun startPingMonitor() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (webSocketClient.isConnected) {
                    lastPingTime = System.currentTimeMillis()
                    // Wir nutzen den WebSocket-Ping-Mechanismus
                    // Der Server sendet periodisch Pings, wir messen die Zeit
                } else {
                    missedPongs++
                    if (missedPongs >= 3) {
                        _uiState.value = _uiState.value.copy(
                            connectionQuality = ConnectionQuality.DISCONNECTED,
                            isConnected = false
                        )
                    }
                }

                // Fallback: Headset-Status regelmäßig prüfen
                // (BroadcastReceiver kann Ereignisse wie Bluetooth-Wiederverbindung verlieren)
                val currentHeadsetPlugged = audioPlayer.isHeadsetPlugged()
                val currentSpeakerOn = audioPlayer.isSpeakerOn()
                if (currentHeadsetPlugged != _uiState.value.isHeadsetPlugged ||
                    currentSpeakerOn != _uiState.value.isSpeakerOn) {
                    Log.d(TAG, "🎧 Headset state mismatch detected in monitor: " +
                        "plugged=${_uiState.value.isHeadsetPlugged}->$currentHeadsetPlugged, " +
                        "speaker=${_uiState.value.isSpeakerOn}->$currentSpeakerOn")
                    soundEffectPlayer.setHeadsetPlugged(currentHeadsetPlugged)
                    soundEffectPlayer.setSpeakerphoneOn(currentSpeakerOn)
                    audioRecorder.setHeadsetPlugged(currentHeadsetPlugged)
                    _uiState.value = _uiState.value.copy(
                        isHeadsetPlugged = currentHeadsetPlugged,
                        isSpeakerOn = currentSpeakerOn
                    )
                }
            }
        }
    }


    /**
     * Registriert einen Callback für Headset-Status-Änderungen vom AudioPlayer.
     * Wird aufgerufen, wenn der BroadcastReceiver im AudioPlayer eine Änderung erkennt.
     * Synchronisiert alle Audio-Komponenten und die UI.
     */
    private fun registerHeadsetCallback() {
        audioPlayer.onHeadsetStateChangeCallback = { plugged ->
            Log.d(TAG, "🎧 Headset state callback: plugged=$plugged")
            soundEffectPlayer.setHeadsetPlugged(plugged)
            audioRecorder.setHeadsetPlugged(plugged)

            // isSpeakerOn vom AudioPlayer übernehmen (wird bei Headset-Wechsel automatisch gesetzt)
            val currentSpeakerState = audioPlayer.isSpeakerOn()
            soundEffectPlayer.setSpeakerphoneOn(currentSpeakerState)

            _uiState.value = _uiState.value.copy(
                isHeadsetPlugged = plugged,
                isSpeakerOn = currentSpeakerState
            )
        }
    }




    fun joinChannel(channel: Channel) {
        Log.d(TAG, "🚪 joinChannel: #${channel.id} '${channel.name}'")
        Log.d(TAG, "   webSocketClient.isConnected=${webSocketClient.isConnected}")

        // Verbindungsstatus vom WebSocket übernehmen (der ist noch verbunden)
        val isConnected = webSocketClient.isConnected
        _uiState.value = _uiState.value.copy(
            channel = channel,
            isConnected = isConnected,
            connectionQuality = if (isConnected) ConnectionQuality.GOOD else ConnectionQuality.UNKNOWN,
            error = null
        )

        // Channel in SavedState speichern
        savedStateHandle["saved_channel_id"] = channel.id
        savedStateHandle["saved_channel_name"] = channel.name

        audioRecorder.setChannelId(channel.id)
        audioPlayer.connectToWebSocket(webSocketClient)
        audioPlayer.startPlayback()
        webSocketClient.joinChannel(channel.id)

        // Headset-Callback registrieren für sofortige UI-Updates
        registerHeadsetCallback()

        // SoundEffectPlayer und AudioRecorder mit aktuellem Audio-Routing synchronisieren
        soundEffectPlayer.setSpeakerphoneOn(audioPlayer.isSpeakerOn())
        val headsetPlugged = audioPlayer.isHeadsetPlugged()
        soundEffectPlayer.setHeadsetPlugged(headsetPlugged)
        audioRecorder.setHeadsetPlugged(headsetPlugged)
        _uiState.value = _uiState.value.copy(
            isSpeakerOn = audioPlayer.isSpeakerOn(),
            isHeadsetPlugged = headsetPlugged
        )

    }

    fun leaveChannel() {
        Log.d(TAG, "🚪 leaveChannel()")
        val channelId = _uiState.value.channel?.id ?: return
        audioRecorder.stopRecording()
        audioPlayer.stopPlayback()
        webSocketClient.leaveChannel(channelId)
        _uiState.value = TalkUiState()

        // SavedState löschen
        savedStateHandle.remove<Int>("saved_channel_id")
        savedStateHandle.remove<String>("saved_channel_name")
    }

    // Debounce: Verhindert, dass startTransmitting() innerhalb von 150ms nach
    // stopTransmitting() aufgerufen wird (z.B. durch verzögerte UI-Events).
    private var lastStopTime = 0L

    fun startTransmitting() {
        Log.d(TAG, "🔴 startTransmitting()")
        val channelId = _uiState.value.channel?.id ?: return

        // Debounce: Wenn stopTransmitting() vor weniger als 150ms aufgerufen wurde,
        // ignoriere diesen Aufruf (verhindert Race Conditions durch den SoundEffectPlayer)
        val now = System.currentTimeMillis()
        if (now - lastStopTime < 150) {
            Log.w(TAG, "   ⏱️ Debounce: startTransmitting() ignored (${now - lastStopTime}ms since stop)")
            return
        }

        if (!webSocketClient.isConnected) {
            Log.w(TAG, "   Not connected, cannot transmit")
            _uiState.value = _uiState.value.copy(
                error = "Keine Verbindung zum Server"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isTransmitting = true)
        webSocketClient.startTalking(channelId)
        audioRecorder.startRecording()
        // ON-Sound für den Sender selbst abspielen
        soundEffectPlayer.playOnSound()
        // Foreground Service benachrichtigen: PTT ist aktiv (für Notification-Timer)
        WalkieApplication.instance.notifyPttStarted()
    }

    fun stopTransmitting() {
        Log.d(TAG, "🟢 stopTransmitting()")
        val channelId = _uiState.value.channel?.id ?: return
        lastStopTime = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(isTransmitting = false)
        webSocketClient.stopTalking(channelId)
        audioRecorder.stopRecording()
        // OFF-Sound für den Sender selbst abspielen
        soundEffectPlayer.playOffSound()
        // Foreground Service benachrichtigen: PTT ist beendet
        WalkieApplication.instance.notifyPttStopped()
    }


    fun toggleTransmitting() {
        val currentState = _uiState.value
        if (currentState.isToggleMode) {
            // Toggle ausschalten
            Log.d(TAG, "🔓 Toggle OFF")
            _uiState.value = currentState.copy(isToggleMode = false, isTransmitting = false)
            audioRecorder.stopRecording()
            val channelId = _uiState.value.channel?.id ?: return
            webSocketClient.stopTalking(channelId)
            // OFF-Sound für den Sender selbst abspielen
            soundEffectPlayer.playOffSound()
            // Foreground Service benachrichtigen: PTT ist beendet
            WalkieApplication.instance.notifyPttStopped()
        } else {
            // Toggle einschalten
            Log.d(TAG, "🔒 Toggle ON")
            _uiState.value = currentState.copy(isToggleMode = true, isTransmitting = true)
            // ✅ Nur startRecording() aufrufen, wenn nicht bereits aufgenommen wird
            // (beim Einrasten aus dem PTT-Modus läuft die Aufnahme bereits)
            if (!audioRecorder.isRecording()) {
                audioRecorder.startRecording()
            } else {
                Log.d(TAG, "   Recording already active, skipping startRecording()")
            }
            val channelId = _uiState.value.channel?.id ?: return
            // ✅ Nur startTalking() senden, wenn nicht bereits gesendet wird
            // (beim Einrasten aus dem PTT-Modus läuft startTalking bereits)
            webSocketClient.startTalking(channelId)
            // ON-Sound nur abspielen, wenn die Aufnahme gerade erst gestartet wurde
            // (beim Einrasten aus dem PTT-Modus wurde der ON-Sound bereits abgespielt)
            if (!currentState.isTransmitting) {
                soundEffectPlayer.playOnSound()
            }
            // Foreground Service benachrichtigen: PTT ist aktiv
            WalkieApplication.instance.notifyPttStarted()
        }
    }


    fun toggleSpeaker() {
        val currentState = _uiState.value
        val newSpeakerState = !currentState.isSpeakerOn
        _uiState.value = currentState.copy(isSpeakerOn = newSpeakerState)
        // Tatsächlich den Audio-Ausgang umschalten (Ohrhörer <-> Lautsprecher)
        audioPlayer.setSpeakerphoneOn(newSpeakerState)
        // SoundEffectPlayer同步更新
        soundEffectPlayer.setSpeakerphoneOn(newSpeakerState)
        // Headset-Status同步 (für alle Audio-Komponenten)
        val headsetPlugged = audioPlayer.isHeadsetPlugged()
        soundEffectPlayer.setHeadsetPlugged(headsetPlugged)
        audioRecorder.setHeadsetPlugged(headsetPlugged)
        _uiState.value = _uiState.value.copy(isHeadsetPlugged = headsetPlugged)
        Log.d(TAG, "🔊 toggleSpeaker: ${if (newSpeakerState) "ON" else "OFF"}, headset=$headsetPlugged")
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 ChannelViewModel.onCleared()")
        pingJob?.cancel()
        audioRecorder.stopRecording()
        audioPlayer.stopPlayback()
    }
}
