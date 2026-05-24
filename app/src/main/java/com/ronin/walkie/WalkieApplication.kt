package com.ronin.walkie

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import com.ronin.walkie.audio.AudioPlayer
import com.ronin.walkie.audio.AudioRecorder
import com.ronin.walkie.network.WalkieWebSocketClient

class WalkieApplication : Application() {

    companion object {
        private const val TAG = "WalkieApp"
        private const val HOST_IP = "192.168.178.78"
        private const val SERVER_PORT = 3000

        val SERVER_URL: String by lazy {
            val url = if (isEmulator()) {
                "ws://10.0.2.2:$SERVER_PORT"
            } else {
                "ws://$HOST_IP:$SERVER_PORT"
            }
            Log.d(TAG, "🔧 SERVER_URL = $url (isEmulator=${isEmulator()}, fingerprint=${Build.FINGERPRINT}, model=${Build.MODEL}, product=${Build.PRODUCT})")
            url
        }
        val HTTP_SERVER_URL: String by lazy {
            val url = if (isEmulator()) {
                "http://10.0.2.2:$SERVER_PORT"
            } else {
                "http://$HOST_IP:$SERVER_PORT"
            }
            Log.d(TAG, "🔧 HTTP_SERVER_URL = $url")
            url
        }

        private fun isEmulator(): Boolean {
            val result = Build.FINGERPRINT.contains("generic") ||
                   Build.FINGERPRINT.contains("emulator") ||
                   Build.MODEL.contains("Emulator") ||
                   Build.MODEL.contains("Android SDK built for x86") ||
                   Build.MANUFACTURER.contains("Genymotion") ||
                   (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                   "google_sdk".equals(Build.PRODUCT)
            Log.d(TAG, "🔍 isEmulator() = $result")
            Log.d(TAG, "   FINGERPRINT=${Build.FINGERPRINT}")
            Log.d(TAG, "   MODEL=${Build.MODEL}")
            Log.d(TAG, "   MANUFACTURER=${Build.MANUFACTURER}")
            Log.d(TAG, "   BRAND=${Build.BRAND}")
            Log.d(TAG, "   DEVICE=${Build.DEVICE}")
            Log.d(TAG, "   PRODUCT=${Build.PRODUCT}")
            return result
        }

        lateinit var instance: WalkieApplication
            private set
    }

    lateinit var webSocketClient: WalkieWebSocketClient
        private set

    lateinit var audioRecorder: AudioRecorder
        private set

    lateinit var audioPlayer: AudioPlayer
        private set

    private var currentActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 WalkieApplication.onCreate()")
        instance = this
        initializeWebSocket()
        audioPlayer = AudioPlayer()
        // AudioManager für Audio-Fokus setzen
        audioPlayer.setAudioManager(this)
        Log.d(TAG, "✅ WalkieApplication initialized. SERVER_URL=$SERVER_URL")
    }

    fun setCurrentActivity(activity: Activity) {
        Log.d(TAG, "🎯 setCurrentActivity(${activity.localClassName})")
        currentActivity = activity
        audioRecorder = AudioRecorder(activity)
        audioRecorder.setWebSocketClient(webSocketClient)
        Log.d(TAG, "✅ AudioRecorder created and linked to WebSocket")
    }

    private fun initializeWebSocket() {
        Log.d(TAG, "🔧 Initializing WebSocket client with URL: $SERVER_URL")
        webSocketClient = WalkieWebSocketClient(SERVER_URL)
        Log.d(TAG, "✅ WebSocket client created")
    }

    fun connectToServer() {
        Log.d(TAG, "🔌 connectToServer() called")
        Log.d(TAG, "   isConnected=${webSocketClient.isConnected}")
        Log.d(TAG, "   isConnecting=${webSocketClient.isConnecting()}")
        Log.d(TAG, "   readyState=${webSocketClient.readyState}")

        if (!webSocketClient.isConnected && !webSocketClient.isConnecting()) {
            Log.d(TAG, "   -> Calling webSocketClient.connect()")
            webSocketClient.connect()
        } else {
            Log.d(TAG, "   -> Already connected or connecting, skipping")
        }
    }

    fun disconnectFromServer() {
        Log.d(TAG, "🔌 disconnectFromServer() called")
        webSocketClient.cancelReconnect()
        if (webSocketClient.isConnected) {
            Log.d(TAG, "   -> Closing WebSocket")
            webSocketClient.close()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "💀 WalkieApplication.onTerminate()")
        audioPlayer.stopPlayback()
        webSocketClient.cancelReconnect()
        if (webSocketClient.isConnected) {
            webSocketClient.close()
        }
    }
}
