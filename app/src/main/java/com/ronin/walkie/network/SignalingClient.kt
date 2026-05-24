package com.ronin.walkie.network

/**
 * SignalingClient wird nicht mehr benötigt.
 * Die Audio-Übertragung erfolgt jetzt direkt über den WebSocket (Audio Relay).
 * 
 * @deprecated Seit v2.0 - Audio wird direkt über WalkieWebSocketClient.sendAudioData() gesendet.
 */
@Deprecated("Nicht mehr benötigt - Audio Relay verwendet direkte WebSocket-Kommunikation")
class SignalingClient(private val webSocketClient: WalkieWebSocketClient) {

    data class SignalData(
        val type: String,
        val from: String,
        val to: String,
        val channelId: Int,
        val data: Any?
    )

    fun parseSignal(payload: Map<String, Any>?): SignalData? {
        return null
    }

    fun sendOffer(sdp: Map<String, Any>, to: String, channelId: Int) {}
    fun sendAnswer(sdp: Map<String, Any>, to: String, channelId: Int) {}
    fun sendIceCandidate(candidate: Map<String, Any>, to: String, channelId: Int) {}
}
