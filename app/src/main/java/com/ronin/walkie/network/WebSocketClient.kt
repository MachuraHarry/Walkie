package com.ronin.walkie.network

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ronin.walkie.model.ServerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.enums.ReadyState
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

/**
 * Verbesserter WebSocket-Client mit:
 * - Exponential Backoff für Reconnect (1s → 2s → 4s → ... → max 30s)
 * - Maximal 10 Reconnect-Versuche, dann aufgeben
 * - Connection-Timeout (10s)
 * - Message-Queue für Offline-Nachrichten
 * - Coroutine-basierte Reconnect-Logik
 * - Lifecycle-aware Verbindungssteuerung
 */
class WalkieWebSocketClient(
    serverUrl: String
) : WebSocketClient(URI(serverUrl)) {

    companion object {
        private const val TAG = "WalkieWS"
        private const val INITIAL_RECONNECT_INTERVAL_MS = 1000L
        private const val MAX_RECONNECT_INTERVAL_MS = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val CONNECTION_LOST_TIMEOUT_MS = 30000
        private const val MAX_QUEUED_MESSAGES = 100
    }

    init {
        Log.d(TAG, "🏗️ WalkieWebSocketClient created with URL: $serverUrl")
        connectionLostTimeout = CONNECTION_LOST_TIMEOUT_MS / 1000
        Log.d(TAG, "   connectionLostTimeout set to ${connectionLostTimeout}s")
    }

    private val gson = Gson()
    private val _messages = MutableSharedFlow<ServerMessage>(replay = 10, extraBufferCapacity = 50)
    val messages: Flow<ServerMessage> = _messages.asSharedFlow()

    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    private var _isReconnecting = false
    private var _hasConnectedEver = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var scope: CoroutineScope? = null

    // Message-Queue für Nachrichten, die während der Trennung gesendet werden
    private val pendingMessages = mutableListOf<Pair<String, Any?>>()

    // Callback für eingehende Audio-Daten (Base64-kodiert)
    var onAudioDataReceived: ((username: String, base64Data: String) -> Unit)? = null

    fun isConnecting(): Boolean {
        val state = (_hasConnectedEver && readyState == ReadyState.NOT_YET_CONNECTED) || _isReconnecting
        Log.d(TAG, "🔍 isConnecting() = $state (readyState=$readyState, _isReconnecting=$_isReconnecting, _hasConnectedEver=$_hasConnectedEver)")
        return state
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        _isConnected = true
        _isReconnecting = false
        reconnectAttempts = 0
        cancelConnectionTimeout()
        Log.d(TAG, "✅✅✅ WebSocket CONNECTED! HTTP status=${handshakedata?.httpStatusMessage}")
        Log.d(TAG, "   ReadyState=$readyState")
        _messages.tryEmit(ServerMessage("connected"))
        Log.d(TAG, "   Emitted 'connected' message to flow")

        // Ausstehende Nachrichten senden
        flushPendingMessages()
    }

    override fun onMessage(message: String) {
        Log.d(TAG, "📩 RAW message received (${message.length} chars): ${message.substring(0, Math.min(message.length, 500))}")
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(message, type)
            val msgType = data["type"] as? String ?: "unknown"
            val payload = data["payload"] as? Map<String, Any>

            Log.d(TAG, "📨 Parsed message: type='$msgType', payload=${payload?.keys ?: "null"}")

            // Audio-Daten direkt per Callback verarbeiten (nicht über Flow)
            if (msgType == "audio_data" && payload != null) {
                val username = payload["username"] as? String ?: return
                val audioData = payload["data"] as? String ?: return
                Log.d(TAG, "🎵 Audio data from '$username': ${audioData.length} base64 chars")
                onAudioDataReceived?.invoke(username, audioData)
                Log.d(TAG, "   Audio callback invoked")
                return
            }

            val serverMessage = ServerMessage(type = msgType, payload = payload)
            Log.d(TAG, "📨 Emitting ServerMessage to flow: type='$msgType'")
            val emitted = _messages.tryEmit(serverMessage)
            Log.d(TAG, "   tryEmit result: $emitted")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing message: $message", e)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        _isConnected = false
        cancelConnectionTimeout()
        Log.d(TAG, "🔌🔌🔌 WebSocket CLOSED: reason='$reason' (code=$code, remote=$remote)")
        Log.d(TAG, "   ReadyState=$readyState")
        _messages.tryEmit(ServerMessage("disconnected", mapOf("reason" to (reason ?: "unknown"))))
        Log.d(TAG, "   Emitted 'disconnected' message to flow")

        if (remote || code != 1000) {
            Log.d(TAG, "   -> Scheduling reconnect (remote=$remote, code=$code)")
            scheduleReconnect()
        } else {
            Log.d(TAG, "   -> NOT scheduling reconnect (normal close)")
        }
    }

    override fun onError(ex: Exception) {
        _isConnected = false
        cancelConnectionTimeout()
        Log.e(TAG, "💥💥💥 WebSocket ERROR: ${ex.message}", ex)
        Log.d(TAG, "   ReadyState=$readyState")
        _messages.tryEmit(ServerMessage("error", mapOf("message" to (ex.message ?: "unknown error"))))
        Log.d(TAG, "   Emitted 'error' message to flow")
    }

    /**
     * Verbindet mit dem Server mit Timeout-Überwachung.
     */
    override fun connect() {
        _hasConnectedEver = true
        Log.d(TAG, "🔌 connect() called (_hasConnectedEver=$_hasConnectedEver)")
        super.connect()
        startConnectionTimeout()
    }

    /**
     * Verbindet mit einem Coroutine-Scope für Lifecycle-Management.
     */
    fun connectWithScope(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        connect()
    }

    /**
     * Startet Connection-Timeout: Wenn nach 10s keine Verbindung steht, abbrechen.
     */
    private fun startConnectionTimeout() {
        cancelConnectionTimeout()
        connectionTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (!_isConnected && !_isReconnecting) {
                Log.w(TAG, "⏰ Connection timeout after ${CONNECTION_TIMEOUT_MS}ms, closing...")
                _messages.tryEmit(ServerMessage("error", mapOf("message" to "Connection timeout")))
                close()
            }
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    /**
     * Verbesserter Reconnect mit Exponential Backoff und maximalen Versuchen.
     */
    private fun scheduleReconnect() {
        Log.d(TAG, "🔄 scheduleReconnect() called (_isReconnecting=$_isReconnecting, attempt=${reconnectAttempts + 1}/$MAX_RECONNECT_ATTEMPTS)")

        if (_isReconnecting) {
            Log.d(TAG, "   Already reconnecting, skipping")
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "❌ Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached. Giving up.")
            _messages.tryEmit(ServerMessage("error", mapOf(
                "message" to "Max reconnect attempts reached. Server may be offline."
            )))
            _isReconnecting = false
            return
        }

        _isReconnecting = true
        reconnectAttempts++

        // Exponential Backoff: 1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
        val delayMs = Math.min(
            INITIAL_RECONNECT_INTERVAL_MS * Math.pow(2.0, (reconnectAttempts - 1).toDouble()).toLong(),
            MAX_RECONNECT_INTERVAL_MS
        )
        Log.d(TAG, "   Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            Log.d(TAG, "🔄🔄🔄 Reconnect attempt $reconnectAttempts starting...")
            Log.d(TAG, "   isConnected=$isConnected, isConnecting=${isConnecting()}")
            if (!isConnected && !isConnecting()) {
                Log.d(TAG, "   -> Calling reconnect()")
                reconnect()
                Log.d(TAG, "   -> reconnect() returned")
            } else {
                Log.d(TAG, "   -> Already connected or connecting, skipping reconnect")
                _isReconnecting = false
            }
        }
    }

    fun cancelReconnect() {
        Log.d(TAG, "🛑 cancelReconnect() called")
        _isReconnecting = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        cancelConnectionTimeout()
    }

    /**
     * Sendet eine Nachricht. Wenn nicht verbunden, wird sie in die Queue gestellt.
     */
    fun sendMessage(type: String, payload: Any? = null) {
        Log.d(TAG, "📤 sendMessage: type='$type', payload=$payload")
        Log.d(TAG, "   isOpen=$isOpen, readyState=$readyState")

        if (!isOpen) {
            Log.w(TAG, "⚠️ Cannot send message, not connected! Queueing message.")
            queueMessage(type, payload)
            return
        }

        val message = mapOf("type" to type, "payload" to payload)
        val json = gson.toJson(message)
        Log.d(TAG, "📤 Sending JSON (${json.length} chars): $json")
        send(json)
        Log.d(TAG, "   send() called")
    }

    /**
     * Sendet Audio-Daten (PCM) als Base64-kodierten String an den Server.
     * Wenn nicht verbunden, wird die Nachricht verworfen (Audio ist zeitkritisch).
     */
    fun sendAudioData(channelId: Int, pcmData: ByteArray) {
        Log.d(TAG, "🎤 sendAudioData: channelId=$channelId, pcmData.size=${pcmData.size}")
        Log.d(TAG, "   isOpen=$isOpen, readyState=$readyState")

        if (!isOpen) {
            Log.w(TAG, "⚠️ Cannot send audio data, not connected! Dropping audio packet.")
            return
        }

        val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        Log.d(TAG, "   Base64 encoded: ${base64Data.length} chars")
        val message = mapOf(
            "type" to "audio_data",
            "payload" to mapOf(
                "channelId" to channelId,
                "data" to base64Data
            )
        )
        val json = gson.toJson(message)
        Log.d(TAG, "📤 Sending audio JSON (${json.length} chars)")
        send(json)
        Log.d(TAG, "   send() called for audio data")
    }

    /**
     * Queue eine Nachricht für späteren Versand, wenn die Verbindung wieder steht.
     */
    private fun queueMessage(type: String, payload: Any?) {
        if (pendingMessages.size >= MAX_QUEUED_MESSAGES) {
            Log.w(TAG, "⚠️ Message queue full ($MAX_QUEUED_MESSAGES). Dropping oldest message.")
            pendingMessages.removeFirst()
        }
        pendingMessages.add(Pair(type, payload))
        Log.d(TAG, "   Message queued. Queue size: ${pendingMessages.size}")
    }

    /**
     * Sendet alle ausstehenden Nachrichten, sobald die Verbindung wieder steht.
     */
    private fun flushPendingMessages() {
        if (pendingMessages.isEmpty()) return
        Log.d(TAG, "📤 Flushing ${pendingMessages.size} pending messages...")
        val messagesToSend = pendingMessages.toList()
        pendingMessages.clear()
        for ((type, payload) in messagesToSend) {
            sendMessage(type, payload)
        }
        Log.d(TAG, "   Flushed ${messagesToSend.size} messages")
    }

    fun login(username: String) {
        Log.d(TAG, "🔑 login('$username')")
        sendMessage("login", mapOf("username" to username))
    }

    fun getChannels() {
        Log.d(TAG, "📋 getChannels()")
        sendMessage("get_channels")
    }

    fun createChannel(name: String, description: String = "", color: String = "#4CAF50") {
        Log.d(TAG, "📢 createChannel: name='$name'")
        sendMessage("create_channel", mapOf(
            "name" to name,
            "description" to description,
            "color" to color
        ))
    }

    fun joinChannel(channelId: Int) {
        Log.d(TAG, "🚪 joinChannel($channelId)")
        sendMessage("join_channel", mapOf("channelId" to channelId))
    }

    fun leaveChannel(channelId: Int) {
        Log.d(TAG, "🚪 leaveChannel($channelId)")
        sendMessage("leave_channel", mapOf("channelId" to channelId))
    }

    fun getUsers(channelId: Int) {
        Log.d(TAG, "👥 getUsers($channelId)")
        sendMessage("get_users", mapOf("channelId" to channelId))
    }

    fun startTalking(channelId: Int) {
        Log.d(TAG, "🔴 startTalking($channelId)")
        sendMessage("start_talking", mapOf("channelId" to channelId))
    }

    fun stopTalking(channelId: Int) {
        Log.d(TAG, "🟢 stopTalking($channelId)")
        sendMessage("stop_talking", mapOf("channelId" to channelId))
    }
}
