package com.ronin.walkie.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ronin.walkie.model.ServerMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class WalkieWebSocketClient(
    serverUrl: String
) : WebSocketClient(URI(serverUrl)) {

    companion object {
        private const val TAG = "WalkieWS"
    }

    private val gson = Gson()
    private val _messages = MutableSharedFlow<ServerMessage>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    override fun onOpen(handshakedata: ServerHandshake?) {
        _isConnected = true
        Log.d(TAG, "WebSocket connected")
        _messages.tryEmit(ServerMessage("connected"))
    }

    override fun onMessage(message: String) {
        Log.d(TAG, "Received: $message")
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(message, type)
            val serverMessage = ServerMessage(
                type = data["type"] as? String ?: "unknown",
                payload = data["payload"] as? Map<String, Any>
            )
            _messages.tryEmit(serverMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $message", e)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        _isConnected = false
        Log.d(TAG, "WebSocket closed: $reason")
        _messages.tryEmit(ServerMessage("disconnected", mapOf("reason" to (reason ?: "unknown"))))
    }

    override fun onError(ex: Exception) {
        _isConnected = false
        Log.e(TAG, "WebSocket error", ex)
        _messages.tryEmit(ServerMessage("error", mapOf("message" to (ex.message ?: "unknown error"))))
    }

    fun sendMessage(type: String, payload: Any? = null) {
        try {
            if (!isOpen) {
                Log.w(TAG, "Cannot send message ($type), not connected")
                return
            }
            val message = mapOf("type" to type, "payload" to payload)
            val json = gson.toJson(message)
            Log.d(TAG, "Sending: $json")
            send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: $type", e)
        }
    }

    fun login(username: String) {
        sendMessage("login", mapOf("username" to username))
    }

    fun getChannels() {
        sendMessage("get_channels")
    }

    fun createChannel(name: String, description: String = "", color: String = "#4CAF50") {
        sendMessage("create_channel", mapOf(
            "name" to name,
            "description" to description,
            "color" to color
        ))
    }

    fun joinChannel(channelId: Int) {
        sendMessage("join_channel", mapOf("channelId" to channelId))
    }

    fun leaveChannel(channelId: Int) {
        sendMessage("leave_channel", mapOf("channelId" to channelId))
    }

    fun getUsers(channelId: Int) {
        sendMessage("get_users", mapOf("channelId" to channelId))
    }

    fun sendSignal(signal: Map<String, Any>) {
        sendMessage("signal", signal)
    }

    fun startTalking(channelId: Int) {
        sendMessage("start_talking", mapOf("channelId" to channelId))
    }

    fun stopTalking(channelId: Int) {
        sendMessage("stop_talking", mapOf("channelId" to channelId))
    }
}
