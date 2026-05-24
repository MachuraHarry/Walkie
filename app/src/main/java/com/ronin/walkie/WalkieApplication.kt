package com.ronin.walkie

import android.app.Application
import com.ronin.walkie.network.SignalingClient
import com.ronin.walkie.network.WalkieWebSocketClient
import com.ronin.walkie.webrtc.WebRTCManager

class WalkieApplication : Application() {

    // Server-Konfiguration
    companion object {
        // Server-IP im lokalen Netzwerk
        const val SERVER_URL = "ws://192.168.178.78:3000"
        const val HTTP_SERVER_URL = "http://192.168.178.78:3000"
        lateinit var instance: WalkieApplication
            private set
    }

    lateinit var webSocketClient: WalkieWebSocketClient
        private set

    lateinit var signalingClient: SignalingClient
        private set

    private var webRTCManager: WebRTCManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        initializeWebSocket()
    }

    private fun initializeWebSocket() {
        webSocketClient = WalkieWebSocketClient(SERVER_URL)
        signalingClient = SignalingClient(webSocketClient)
    }

    fun createWebRTCManager(username: String): WebRTCManager {
        val manager = WebRTCManager(this, signalingClient, username)
        webRTCManager = manager
        return manager
    }

    fun getWebRTCManager(): WebRTCManager? = webRTCManager

    fun connectToServer() {
        if (!webSocketClient.isConnected) {
            webSocketClient.connect()
        }
    }

    fun disconnectFromServer() {
        if (webSocketClient.isConnected) {
            webSocketClient.close()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        webRTCManager?.disconnectAll()
        if (webSocketClient.isConnected) {
            webSocketClient.close()
        }
    }
}
