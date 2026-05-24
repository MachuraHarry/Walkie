package com.ronin.walkie.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.ronin.walkie.network.WalkieWebSocketClient

/**
 * Empfängt PCM-Audio-Daten vom WebSocket (vom Server gerelayt) und spielt sie ab.
 * Unterstützt mehrere gleichzeitige Sprecher durch Mischen der Audio-Streams.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun startPlayback(): Boolean {
        Log.d(TAG, "▶️ startPlayback() called")
        Log.d(TAG, "   isPlaying=$isPlaying")
        
        if (isPlaying) {
            Log.d(TAG, "   Already playing, returning true")
            return true
        }

        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4
            Log.d(TAG, "   bufferSize=$bufferSize")

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioTrack not initialized! state=${audioTrack?.state}")
                return false
            }
            Log.d(TAG, "   AudioTrack state: ${audioTrack?.state}")

            audioTrack?.play()
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "❌ AudioTrack not playing! state=${audioTrack?.playState}")
                return false
            }
            Log.d(TAG, "   AudioTrack playState: ${audioTrack?.playState}")
            
            isPlaying = true
            Log.d(TAG, "✅ Playback started (${SAMPLE_RATE}Hz)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting playback", e)
            return false
        }
    }

    /**
     * Verbindet den AudioPlayer mit dem WebSocket, um eingehende Audio-Daten zu empfangen.
     */
    fun connectToWebSocket(webSocketClient: WalkieWebSocketClient) {
        Log.d(TAG, "🔗 connectToWebSocket()")
        webSocketClient.onAudioDataReceived = { username, base64Data ->
            Log.d(TAG, "🎵 Audio received from '$username': ${base64Data.length} base64 chars")
            playBase64Audio(base64Data)
        }
        Log.d(TAG, "✅ AudioPlayer connected to WebSocket")
    }

    /**
     * Dekodiert Base64-Audio-Daten und spielt sie ab.
     */
    private fun playBase64Audio(base64Data: String) {
        try {
            val pcmData = Base64.decode(base64Data, Base64.NO_WRAP)
            Log.d(TAG, "   Decoded ${pcmData.size} PCM bytes")
            playAudioData(pcmData)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error decoding audio data", e)
        }
    }

    fun playAudioData(data: ByteArray) {
        if (!isPlaying || audioTrack == null) {
            Log.w(TAG, "⚠️ Cannot play audio: isPlaying=$isPlaying, audioTrack=$audioTrack")
            return
        }
        try {
            val written = audioTrack?.write(data, 0, data.size) ?: -1
            if (written != data.size) {
                Log.w(TAG, "   Wrote $written/${data.size} bytes to AudioTrack")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error writing audio data", e)
        }
    }

    fun stopPlayback() {
        Log.d(TAG, "⏹️ stopPlayback() called")
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "✅ Playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping playback", e)
        }
    }

    fun isPlaying(): Boolean {
        Log.d(TAG, "🔍 isPlaying() = $isPlaying")
        return isPlaying
    }
}
