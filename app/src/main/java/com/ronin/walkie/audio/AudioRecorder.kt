package com.ronin.walkie.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.app.Activity
import com.ronin.walkie.network.WalkieWebSocketClient
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Verbesserter AudioRecorder mit:
 * - Coroutine-basiert statt Thread
 * - Audio-Fokus-Management (respektiert Telefonanrufe)
 * - Silence-Detection (VAD - Voice Activity Detection)
 * - Robuster Fehlerbehandlung
 * - Headset-Unterstützung: Bei angeschlossenen Kopfhörern wird das Headset-Mikrofon verwendet
 */
class AudioRecorder(private val activity: Activity) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
        // Audio-Pakete alle 100ms senden (1600 Bytes bei 16kHz, 16bit, Mono)
        private const val AUDIO_CHUNK_MS = 100

        // Silence-Detection Schwellwerte
        private const val SILENCE_THRESHOLD = 500   // RMS-Schwellwert für Stille
        private const val SILENCE_TIMEOUT_MS = 2000L // 2s Stille = automatisch stoppen
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var bufferSize = 0
    private var recordingJob: Job? = null
    private var webSocketClient: WalkieWebSocketClient? = null
    private var currentChannelId: Int = 0
    private var audioManager: AudioManager? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var hasAudioFocus = false
    private var silenceStartTime: Long = 0
    private var isPausedByFocusLoss = false
    private var isHeadsetPlugged = false
    private var isBluetoothScoStarted = false

    // Hintergrund-Thread für AudioManager-Operationen (SCO, Routing)
    private val audioHandler: android.os.Handler
    private val audioThread: android.os.HandlerThread

    init {
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER
        audioManager = activity.getSystemService(Activity.AUDIO_SERVICE) as? AudioManager
        audioThread = android.os.HandlerThread("AudioRecorder-Audio")
        audioThread.start()
        audioHandler = android.os.Handler(audioThread.looper)
        Log.d(TAG, "🏗️ AudioRecorder created, bufferSize=$bufferSize")
    }


    fun setWebSocketClient(client: WalkieWebSocketClient) {
        Log.d(TAG, "🔗 setWebSocketClient: $client")
        webSocketClient = client
    }

    fun setChannelId(channelId: Int) {
        Log.d(TAG, "📌 setChannelId($channelId)")
        currentChannelId = channelId
    }

    /**
     * Setzt den Headset-Status für korrektes Audio-Routing.
     * Wenn Kopfhörer mit Mikrofon angeschlossen sind, wird über das Headset-Mikrofon aufgenommen.
     */
    fun setHeadsetPlugged(plugged: Boolean) {
        isHeadsetPlugged = plugged
        Log.d(TAG, "🎧 AudioRecorder headset state: $plugged")
    }

    fun hasPermission(): Boolean {
        val result = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "🔍 hasPermission() = $result")
        return result
    }

    fun requestPermission(requestCode: Int = 1001) {
        Log.d(TAG, "📋 requestPermission($requestCode)")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            requestCode
        )
    }

    /**
     * Fordert Audio-Fokus an (wichtig für Telefonanrufe).
     */
    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) return true

        audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Dauerhafter Fokus-Verlust (z.B. Telefonanruf) → Aufnahme stoppen
                    Log.w(TAG, "🔇 Audio focus LOST. Stopping recording.")
                    hasAudioFocus = false
                    if (isRecording) {
                        isPausedByFocusLoss = true
                        stopRecordingInternal()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Kurzer Fokus-Verlust (z.B. eigener SoundEffectPlayer) → NICHT stoppen!
                    // Der SoundEffectPlayer verwendet FLAG_ALLOW_IN_REDUCTION_NO_FOCUS,
                    // daher sollte dies nicht passieren. Falls doch, ignorieren wir es,
                    // da es nur ein kurzer Sound ist und die Aufnahme nicht unterbrochen werden soll.
                    Log.w(TAG, "🔇 Audio focus LOST (transient). IGNORING - continuing recording.")
                    hasAudioFocus = false
                    // NICHT stoppen - der Fokus kommt sofort zurück
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "🔉 Audio focus lost (can duck). Continuing recording.")
                    // Wir können weiter aufnehmen, aber leiser
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.d(TAG, "🔊 Audio focus GAINED.")
                    hasAudioFocus = true
                    // Wenn wir wegen Fokus-Verlust pausiert haben, wieder starten
                    if (isPausedByFocusLoss && currentChannelId > 0) {
                        isPausedByFocusLoss = false
                        Log.d(TAG, "   Resuming recording after focus gain")
                    }
                }
            }
        }


        // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: Erlaubt anderen Apps/Sounds, sich zu melden,
        // ohne dass die Aufnahme unterbrochen wird. Verhindert, dass der SoundEffectPlayer
        // (MediaPlayer) der Walkie-App selbst den Fokus entzieht und die Aufnahme abbricht.
        val result = audioManager?.requestAudioFocus(
            audioFocusListener!!,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )


        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "🔊 Audio focus request: ${if (hasAudioFocus) "GRANTED" else "DENIED"}")
        return hasAudioFocus
    }

    /**
     * Gibt Audio-Fokus wieder frei.
     */
    private fun abandonAudioFocus() {
        audioFocusListener?.let {
            audioManager?.abandonAudioFocus(it)
            audioFocusListener = null
        }
        hasAudioFocus = false
        isPausedByFocusLoss = false
    }

    /**
     * Voice Activity Detection: Prüft, ob ein Audio-Puffer Stille enthält.
     * Berechnet den RMS (Root Mean Square) des Signals.
     */
    private fun isSilence(audioData: ByteArray): Boolean {
        if (audioData.isEmpty()) return true

        var sumSquares = 0.0
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val sample = (audioData[i].toInt() and 0xFF) or
                        (audioData[i + 1].toInt() shl 8)
                sumSquares += (sample * sample).toDouble()
            }
        }
        val rms = Math.sqrt(sumSquares / (audioData.size / 2))
        return rms < SILENCE_THRESHOLD
    }

    /**
     * Konfiguriert den AudioManager für die Aufnahme über Bluetooth-Headset-Mikrofon.
     * Dies ist entscheidend, damit Android den Bluetooth-SCO-Kanal für das Mikrofon verwendet.
     */
    private fun configureAudioForRecording() {
        val am = audioManager ?: return

        // Wichtig: MODE_IN_COMMUNICATION muss gesetzt werden, damit Bluetooth SCO funktioniert
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (isHeadsetPlugged && (am.isBluetoothA2dpOn || am.isBluetoothScoAvailableOffCall())) {
            try {
                // Bluetooth SCO (Synchronous Connection Oriented) starten
                // Dies ist der Kanal, über den Bluetooth-Headset-Mikrofone Audio übertragen
                if (!isBluetoothScoStarted) {
                    am.startBluetoothSco()
                    isBluetoothScoStarted = true
                    Log.d(TAG, "🎧 Bluetooth SCO started for headset microphone")
                }
                am.isBluetoothScoOn = true
                am.isSpeakerphoneOn = false
                Log.d(TAG, "🎧 Bluetooth SCO routing enabled for recording")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error configuring Bluetooth SCO", e)
            }
        } else {
            // Kein Bluetooth-Headset - normales Mikrofon-Routing
            am.isBluetoothScoOn = false
            Log.d(TAG, "🎤 Using device microphone (no Bluetooth headset)")
        }
    }

    /**
     * Stellt die Audio-Konfiguration nach der Aufnahme wieder her.
     */
    private fun restoreAudioAfterRecording() {
        val am = audioManager ?: return

        try {
            if (isBluetoothScoStarted) {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
                isBluetoothScoStarted = false
                Log.d(TAG, "🎧 Bluetooth SCO stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping Bluetooth SCO", e)
        }
    }

    fun startRecording(): Boolean {
        Log.d(TAG, "🎤 startRecording() called")
        Log.d(TAG, "   hasPermission=${hasPermission()}")
        Log.d(TAG, "   isRecording=$isRecording")
        Log.d(TAG, "   webSocketClient=${webSocketClient}")
        Log.d(TAG, "   currentChannelId=$currentChannelId")
        Log.d(TAG, "   isHeadsetPlugged=$isHeadsetPlugged")

        if (!hasPermission()) {
            Log.e(TAG, "❌ No audio permission")
            return false
        }

        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording")
            return true
        }

        if (webSocketClient == null) {
            Log.e(TAG, "❌ WebSocket client not set")
            return false
        }

        // Audio-Fokus und AudioManager-Konfiguration auf Hintergrund-Thread ausführen
        // (diese Operationen blockieren den Main-Thread und verursachen Ruckeln)
        audioHandler.post {
            configureAudioForRecording()
        }
        requestAudioFocus()

        try {
            Log.d(TAG, "   Creating AudioRecord: rate=$SAMPLE_RATE, bufferSize=$bufferSize")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord not initialized! state=${audioRecord?.state}")
                audioHandler.post {
                    restoreAudioAfterRecording()
                }
                abandonAudioFocus()
                return false
            }
            Log.d(TAG, "   AudioRecord state: ${audioRecord?.state}")

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ AudioRecord not recording! state=${audioRecord?.recordingState}")
                audioHandler.post {
                    restoreAudioAfterRecording()
                }
                abandonAudioFocus()
                return false
            }
            Log.d(TAG, "   AudioRecord recording state: ${audioRecord?.recordingState}")

            isRecording = true
            silenceStartTime = 0L

            val chunkSize = SAMPLE_RATE * 2 * AUDIO_CHUNK_MS / 1000 // 16bit = 2 Bytes
            Log.d(TAG, "   chunkSize=$chunkSize bytes (${AUDIO_CHUNK_MS}ms)")

            // Coroutine-basierte Aufnahme
            recordingJob = CoroutineScope(Dispatchers.IO + CoroutineName("AudioRecorder")).launch {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "   Recording coroutine started")

                val buffer = ByteArray(chunkSize)
                var totalBytesSent = 0
                var chunkCount = 0
                var silentChunks = 0

                while (isRecording && isActive) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0 && currentChannelId > 0) {
                        val dataToSend = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer

                        // Silence-Detection
                        if (isSilence(dataToSend)) {
                            silentChunks++
                            if (silentChunks >= SILENCE_TIMEOUT_MS / AUDIO_CHUNK_MS) {
                                // Zu lange Stille - automatisch stoppen
                                Log.d(TAG, "   Silence detected for ${silentChunks * AUDIO_CHUNK_MS}ms, stopping...")
                                // Sende trotzdem noch ein letztes Paket (kann sein, dass der Sprecher leise war)
                                webSocketClient?.sendAudioData(currentChannelId, dataToSend)
                                break
                            }
                            // Überspringe Stille-Pakete (spart Bandbreite)
                            continue
                        } else {
                            silentChunks = 0
                        }

                        webSocketClient?.sendAudioData(currentChannelId, dataToSend)
                        totalBytesSent += dataToSend.size
                        chunkCount++
                        if (chunkCount % 10 == 0) { // Alle 1 Sekunde loggen
                            Log.d(TAG, "   Sent $chunkCount chunks, $totalBytesSent bytes total")
                        }
                    } else if (bytesRead <= 0) {
                        Log.w(TAG, "   AudioRecord.read returned $bytesRead")
                        if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                            Log.e(TAG, "❌ AudioRecord invalid operation, stopping")
                            break
                        }
                    }
                }
                Log.d(TAG, "   Recording coroutine ended. Sent $chunkCount chunks, $totalBytesSent bytes total")

                // Aufräumen
                if (isRecording) {
                    stopRecordingInternal()
                }
            }

            Log.d(TAG, "✅ Recording started (${SAMPLE_RATE}Hz, ${AUDIO_CHUNK_MS}ms chunks)")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception starting recording", e)
            audioHandler.post {
                restoreAudioAfterRecording()
            }
            abandonAudioFocus()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting recording", e)
            audioHandler.post {
                restoreAudioAfterRecording()
            }
            abandonAudioFocus()
            return false
        }
    }


    private fun stopRecordingInternal() {
        Log.d(TAG, "🛑 stopRecordingInternal() called")
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
            audioRecord = null
            Log.d(TAG, "✅ Recording stopped (internal)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping recording (internal)", e)
        }
    }

    fun stopRecording() {
        Log.d(TAG, "🛑 stopRecording() called")
        isPausedByFocusLoss = false
        stopRecordingInternal()
        // AudioManager-Operationen auf Hintergrund-Thread ausführen
        audioHandler.post {
            restoreAudioAfterRecording()
            abandonAudioFocus()
        }
    }


    fun isRecording(): Boolean {
        Log.d(TAG, "🔍 isRecording() = $isRecording")
        return isRecording
    }
}
