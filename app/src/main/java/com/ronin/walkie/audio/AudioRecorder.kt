package com.ronin.walkie.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.app.Activity
import com.ronin.walkie.network.WalkieWebSocketClient

/**
 * Nimmt PCM-Audio vom Mikrofon auf und sendet es direkt über den WebSocket an den Server.
 * Der Server relayed die Audio-Daten an alle anderen Clients im selben Channel.
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
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var bufferSize = 0
    private var recordingThread: Thread? = null
    private var webSocketClient: WalkieWebSocketClient? = null
    private var currentChannelId: Int = 0

    init {
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER
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

    fun startRecording(): Boolean {
        Log.d(TAG, "🎤 startRecording() called")
        Log.d(TAG, "   hasPermission=${hasPermission()}")
        Log.d(TAG, "   isRecording=$isRecording")
        Log.d(TAG, "   webSocketClient=${webSocketClient}")
        Log.d(TAG, "   currentChannelId=$currentChannelId")
        
        if (!hasPermission()) {
            Log.e(TAG, "❌ No audio permission")
            return false
        }

        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording")
            return false
        }

        if (webSocketClient == null) {
            Log.e(TAG, "❌ WebSocket client not set")
            return false
        }

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
                return false
            }
            Log.d(TAG, "   AudioRecord state: ${audioRecord?.state}")

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ AudioRecord not recording! state=${audioRecord?.recordingState}")
                return false
            }
            Log.d(TAG, "   AudioRecord recording state: ${audioRecord?.recordingState}")
            
            isRecording = true

            val chunkSize = SAMPLE_RATE * 2 * AUDIO_CHUNK_MS / 1000 // 16bit = 2 Bytes
            Log.d(TAG, "   chunkSize=$chunkSize bytes (${AUDIO_CHUNK_MS}ms)")

            recordingThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "   Recording thread started")
                
                val buffer = ByteArray(chunkSize)
                var totalBytesSent = 0
                var chunkCount = 0

                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0 && currentChannelId > 0) {
                        val dataToSend = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                        webSocketClient?.sendAudioData(currentChannelId, dataToSend)
                        totalBytesSent += dataToSend.size
                        chunkCount++
                        if (chunkCount % 10 == 0) { // Alle 1 Sekunde loggen
                            Log.d(TAG, "   Sent $chunkCount chunks, $totalBytesSent bytes total")
                        }
                    } else if (bytesRead <= 0) {
                        Log.w(TAG, "   AudioRecord.read returned $bytesRead")
                    }
                }
                Log.d(TAG, "   Recording thread ended. Sent $chunkCount chunks, $totalBytesSent bytes total")
            }.apply { 
                name = "AudioRecorder-Thread"
                start() 
            }

            Log.d(TAG, "✅ Recording started (${SAMPLE_RATE}Hz, ${AUDIO_CHUNK_MS}ms chunks)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting recording", e)
            return false
        }
    }

    fun stopRecording() {
        Log.d(TAG, "🛑 stopRecording() called")
        isRecording = false
        try {
            recordingThread?.join(500)
            Log.d(TAG, "   Recording thread joined")
        } catch (e: InterruptedException) {
            Log.w(TAG, "   Interrupted while joining recording thread", e)
        }
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "✅ Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping recording", e)
        }
    }

    fun isRecording(): Boolean {
        Log.d(TAG, "🔍 isRecording() = $isRecording")
        return isRecording
    }
}
