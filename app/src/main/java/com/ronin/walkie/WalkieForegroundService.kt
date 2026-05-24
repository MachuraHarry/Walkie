package com.ronin.walkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ronin.walkie.audio.AudioPlayer
import com.ronin.walkie.audio.AudioRecorder
import com.ronin.walkie.audio.SoundEffectPlayer
import com.ronin.walkie.network.WalkieWebSocketClient

/**
 * Foreground Service, der die Walkie-App auch im Hintergrund (bei gesperrtem Display)
 * am Leben hält.
 *
 * Der Service zeigt eine dauerhafte Benachrichtigung an:
 * - Normal: "Walkie - Verbunden mit #Channel"
 * - Bei PTT aktiv: "🎤 Sende... (XXs)" mit Live-Timer
 *
 * Lifecycle:
 * - Wird von MainActivity gestartet, sobald der Benutzer in einem Channel ist
 * - Wird gestoppt, wenn der Benutzer den Channel verlässt oder die App beendet
 * - Bei Display-Sperre: Service läuft weiter → WebSocket bleibt verbunden
 * - Bei PTT aktiv + Display-Sperre: Audio-Aufnahme läuft weiter
 */
class WalkieForegroundService : Service() {

    companion object {
        private const val TAG = "WalkieFGS"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "walkie_foreground_channel"
        private const val CHANNEL_NAME = "Walkie Hintergrunddienst"
        private const val CHANNEL_DESC = "Hält die Walkie-Verbindung im Hintergrund aktiv"

        // Action-Konstanten für Intents
        const val ACTION_START = "com.ronin.walkie.action.START_FOREGROUND"
        const val ACTION_STOP = "com.ronin.walkie.action.STOP_FOREGROUND"
        const val ACTION_PTT_START = "com.ronin.walkie.action.PTT_START"
        const val ACTION_PTT_STOP = "com.ronin.walkie.action.PTT_STOP"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }

    private var isRunning = false
    private var channelName = "Walkie"
    private var isPttActive = false
    private var pttStartTime = 0L

    // Handler für den Live-Timer in der Notification
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pttTimerRunnable = object : Runnable {
        override fun run() {
            if (isPttActive) {
                updateNotification()
                mainHandler.postDelayed(this, 1000) // Alle 1 Sekunde aktualisieren
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🏗️ WalkieForegroundService.onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Walkie"
                startForegroundService()
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
            ACTION_PTT_START -> {
                onPttStarted()
            }
            ACTION_PTT_STOP -> {
                onPttStopped()
            }
        }

        // Wenn der Service gekillt wird, nicht neu starten (die Activity startet ihn neu)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Kein Binding nötig, reiner Started Service
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 WalkieForegroundService.onDestroy()")
        mainHandler.removeCallbacks(pttTimerRunnable)
        isRunning = false
        isPttActive = false
    }

    /**
     * Erstellt den Notification-Channel (ab Android 8.0 erforderlich).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Niedrige Priorität, kein Sound
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created: $CHANNEL_ID")
        }
    }

    /**
     * Startet den Service im Vordergrund mit einer Benachrichtigung.
     */
    private fun startForegroundService() {
        if (isRunning) {
            Log.d(TAG, "   Service already running, updating notification")
            updateNotification()
            return
        }

        isRunning = true
        Log.d(TAG, "🚀 Starting foreground service for channel: $channelName")

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "✅ Foreground service started")
    }

    /**
     * Stoppt den Foreground Service.
     */
    private fun stopForegroundService() {
        Log.d(TAG, "🛑 Stopping foreground service")
        mainHandler.removeCallbacks(pttTimerRunnable)
        isRunning = false
        isPttActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "✅ Foreground service stopped")
    }

    /**
     * Wird aufgerufen, wenn PTT aktiviert wird.
     * Startet den Live-Timer in der Notification.
     */
    private fun onPttStarted() {
        Log.d(TAG, "🔴 PTT started")
        isPttActive = true
        pttStartTime = System.currentTimeMillis()
        updateNotification()
        // Live-Timer starten (alle 1 Sekunde aktualisieren)
        mainHandler.removeCallbacks(pttTimerRunnable)
        mainHandler.post(pttTimerRunnable)
    }

    /**
     * Wird aufgerufen, wenn PTT deaktiviert wird.
     * Stoppt den Live-Timer und zeigt wieder den normalen Status.
     */
    private fun onPttStopped() {
        Log.d(TAG, "🟢 PTT stopped")
        isPttActive = false
        mainHandler.removeCallbacks(pttTimerRunnable)
        updateNotification()
    }

    /**
     * Baut die Benachrichtigung für den Foreground Service.
     * Zeigt PTT-Status mit Live-Timer wenn aktiv.
     */
    private fun buildNotification(): Notification {
        val title = if (isPttActive) "🎤 Walkie - Sende..." else "Walkie"
        val text = if (isPttActive) {
            val duration = (System.currentTimeMillis() - pttStartTime) / 1000
            "Verbunden mit #$channelName · ${duration}s"
        } else {
            "Verbunden mit #$channelName"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Kann nicht weggewischt werden
            .setSilent(true) // Kein Sound
            .build()
    }

    /**
     * Aktualisiert die Benachrichtigung.
     */
    private fun updateNotification() {
        if (!isRunning) return
        val notification = buildNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
