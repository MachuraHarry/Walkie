package com.ronin.walkie.network

import android.util.Log
import com.ronin.walkie.model.SignalMessage

class SignalingClient(
    private val webSocketClient: WalkieWebSocketClient
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    fun sendOffer(offer: Any, to: String, channelId: Int) {
        webSocketClient.sendSignal(mapOf(
            "type" to "offer",
            "to" to to,
            "channelId" to channelId,
            "data" to offer
        ))
    }

    fun sendAnswer(answer: Any, to: String, channelId: Int) {
        webSocketClient.sendSignal(mapOf(
            "type" to "answer",
            "to" to to,
            "channelId" to channelId,
            "data" to answer
        ))
    }

    fun sendIceCandidate(candidate: Any, to: String, channelId: Int) {
        webSocketClient.sendSignal(mapOf(
            "type" to "ice_candidate",
            "to" to to,
            "channelId" to channelId,
            "data" to candidate
        ))
    }

    fun parseSignal(payload: Map<String, Any>): SignalMessage? {
        return try {
            SignalMessage(
                type = payload["type"] as? String ?: return null,
                from = payload["from"] as? String ?: "",
                to = payload["to"] as? String ?: "",
                channelId = (payload["channelId"] as? Double)?.toInt() ?: 0,
                data = payload["data"]
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing signal", e)
            null
        }
    }
}
