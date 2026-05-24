package com.ronin.walkie.network

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ronin.walkie.model.ServerMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.enums.ReadyState
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class WalkieWebSocketClient(
    serverUrl: String
) : WebSocketClient(URI(serverUrl)) {

    companion object {
        private const val TAG = "WalkieWS"
        private const val RECONNECT_INTERVAL_MS = 5000L
        private const val CONNECTION_LOST_TIMEOUT_MS = 30000
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    // Callback für eingehende Audio-Daten (Base64-kodiert)
    var onAudioDataReceived: ((username: String, base64Data: String) -> Unit)? = null

    fun isConnecting(): Boolean {
        // NOT_YET_CONNECTED ist auch der Initialzustand direkt nach der Erstellung.
        // Wenn noch nie connect() aufgerufen wurde, gilt das nicht als "verbindet gerade".
        val state = (_hasConnectedEver && readyState == ReadyState.NOT_YET_CONNECTED) || _isReconnecting
        Log.d(TAG, "🔍 isConnecting() = $state (readyState=$readyState, _isReconnecting=$_isReconnecting, _hasConnectedEver=$_hasConnectedEver)")
        return state
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        _isConnected = true
        _isReconnecting = false
        Log.d(TAG, "✅✅✅ WebSocket CONNECTED! HTTP status=${handshakedata?.httpStatusMessage}")
        Log.d(TAG, "   ReadyState=$readyState")
        _messages.tryEmit(ServerMessage("connected"))
        Log.d(TAG, "   Emitted 'connected' message to flow")
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
        Log.e(TAG, "💥💥💥 WebSocket ERROR: ${ex.message}", ex)
        Log.d(TAG, "   ReadyState=$readyState")
        _messages.tryEmit(ServerMessage("error", mapOf("message" to (ex.message ?: "unknown error"))))
        Log.d(TAG, "   Emitted 'error' message to flow")
    }

    private fun scheduleReconnect() {
        Log.d(TAG, "🔄 scheduleReconnect() called (_isReconnecting=$_isReconnecting)")
        if (_isReconnecting) {
            Log.d(TAG, "   Already reconnecting, skipping")
            return
        }
        _isReconnecting = true

        reconnectRunnable?.let { 
            Log.d(TAG, "   Removing existing reconnect runnable")
            mainHandler.removeCallbacks(it) 
        }
        val runnable: Runnable = Runnable {
            Log.d(TAG, "🔄🔄🔄 Reconnect attempt starting...")
            Log.d(TAG, "   isConnected=$isConnected, isConnecting=${isConnecting()}")
            if (!isConnected && !isConnecting()) {
                Log.d(TAG, "   -> Calling reconnect()")
                reconnect()
                Log.d(TAG, "   -> reconnect() returned")
            } else {
                Log.d(TAG, "   -> Already connected or connecting, skipping reconnect")
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, RECONNECT_INTERVAL_MS)
        Log.d(TAG, "   Reconnect scheduled in ${RECONNECT_INTERVAL_MS}ms")
    }

    override fun connect() {
        _hasConnectedEver = true
        Log.d(TAG, "🔌 connect() called (_hasConnectedEver=$_hasConnectedEver)")
        super.connect()
    }

    fun cancelReconnect() {
        Log.d(TAG, "🛑 cancelReconnect() called")
        _isReconnecting = false
        reconnectRunnable?.let { 
            Log.d(TAG, "   Removing reconnect runnable from handler")
            mainHandler.removeCallbacks(it) 
        }
        reconnectRunnable = null
    }

    fun sendMessage(type: String, payload: Any? = null) {
        Log.d(TAG, "📤 sendMessage: type='$type', payload=$payload")
        Log.d(TAG, "   isOpen=$isOpen, readyState=$readyState")
        if (!isOpen) {
            Log.w(TAG, "⚠️ Cannot send message, not connected!")
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
     * Der Server relayed die Daten an alle anderen Clients im selben Channel.
     */
    fun sendAudioData(channelId: Int, pcmData: ByteArray) {
        Log.d(TAG, "🎤 sendAudioData: channelId=$channelId, pcmData.size=${pcmData.size}")
        Log.d(TAG, "   isOpen=$isOpen, readyState=$readyState")
        if (!isOpen) {
            Log.w(TAG, "⚠️ Cannot send audio data, not connected!")
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
