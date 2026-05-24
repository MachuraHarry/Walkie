package com.ronin.walkie.model

data class WebSocketMessage(
    val type: String,
    val payload: Any? = null
)

data class ServerMessage(
    val type: String,
    val payload: Map<String, Any>? = null
)

data class SignalMessage(
    val type: String, // "offer" | "answer" | "ice_candidate"
    val from: String = "",
    val to: String = "",
    val channelId: Int = 0,
    val data: Any? = null
)

data class TalkingStatus(
    val username: String = "",
    val channelId: Int = 0,
    val isTalking: Boolean = false
)
