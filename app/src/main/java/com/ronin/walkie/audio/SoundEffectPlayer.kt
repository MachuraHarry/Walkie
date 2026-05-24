package com.ronin.walkie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.ronin.walkie.R
import com.ronin.walkie.settings.SettingsManager

/**
 * Spielt kurze Soundeffekte (on.mp3 / off.mp3) ab, wie bei einem echten Walkie-Talkie.
 * - on.mp3: Wird abgespielt, wenn jemand anfängt zu senden (bei Sender + allen Zuhörern)
 * - off.mp3: Wird abgespielt, wenn jemand aufhört zu senden (bei Sender + allen Zuhörern)
 *
 * Verwendet MediaPlayer.create() für zuverlässige Ressourcen-Verwaltung.
 * Läuft auf einem Hintergrund-Thread, um den Main-Thread nicht zu blockieren.
 */
class SoundEffectPlayer {

    companion object {
        private const val TAG = "SoundEffectPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var context: Context? = null
    private var audioManager: AudioManager? = null
    private var isSpeakerOn = true
    private var isHeadsetPlugged = false
    private var settingsManager: SettingsManager? = null

    // Hintergrund-Thread für MediaPlayer-Operationen
    private val soundThread = HandlerThread("SoundEffectPlayer")
    private val soundHandler: Handler

    init {
        soundThread.start()
        soundHandler = Handler(soundThread.looper)
    }

    fun setContext(context: Context) {
        this.context = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        settingsManager = SettingsManager(context)
    }

    /**
     * Aktualisiert die Lautsprecher-Einstellung, damit Soundeffekte
     * über die richtige Audio-Quelle abgespielt werden.
     */
    fun setSpeakerphoneOn(on: Boolean) {
        isSpeakerOn = on
    }

    /**
     * Aktualisiert den Headset-Status, damit Soundeffekte
     * über die richtige Audio-Quelle abgespielt werden.
     */
    fun setHeadsetPlugged(plugged: Boolean) {
        isHeadsetPlugged = plugged
    }

    /**
     * Spielt den "Start Talking" Sound (on.mp3).
     */
    fun playOnSound() {
        Log.d(TAG, "🔊 Playing ON sound")
        playSound(R.raw.on)
    }

    /**
     * Spielt den "Stop Talking" Sound (off.mp3).
     */
    fun playOffSound() {
        Log.d(TAG, "🔊 Playing OFF sound")
        playSound(R.raw.off)
    }

    /**
     * Spielt eine Sound-Ressource ab.
     * Läuft komplett auf einem Hintergrund-Thread, um den Main-Thread nicht zu blockieren.
     * MediaPlayer.create() und start() sind asynchrone Operationen, die auf dem
     * Hintergrund-Thread ausgeführt werden.
     */
    private fun playSound(resId: Int) {
        soundHandler.post {
            try {
                // Prüfen, ob Sound-Effekte aktiviert sind
                val sm = settingsManager
                if (sm != null && !sm.isSoundEnabled()) {
                    Log.d(TAG, "🔇 Sound effects disabled, skipping playback")
                    return@post
                }

                // Vorherigen MediaPlayer stoppen und freigeben
                releaseMediaPlayer()

                val ctx = context ?: run {
                    Log.w(TAG, "⚠️ Context not set, cannot play sound")
                    return@post
                }

                // MediaPlayer auf Hintergrund-Thread erstellen
                val mp = MediaPlayer.create(ctx, resId)
                if (mp == null) {
                    Log.e(TAG, "❌ MediaPlayer.create() returned null for resource $resId")
                    return@post
                }

                // Audio-Attribute für Voice Communication setzen.
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )

                mediaPlayer = mp

                mp.setOnCompletionListener { player ->
                    Log.d(TAG, "✅ Sound playback completed")
                    player.release()
                    if (mediaPlayer == player) {
                        mediaPlayer = null
                    }
                }

                mp.setOnErrorListener { player, what, extra ->
                    Log.e(TAG, "❌ MediaPlayer error: what=$what, extra=$extra")
                    player.release()
                    if (mediaPlayer == player) {
                        mediaPlayer = null
                    }
                    true
                }

                mp.start()
                Log.d(TAG, "▶️ Sound started playing (speaker=$isSpeakerOn, headset=$isHeadsetPlugged)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing sound", e)
                releaseMediaPlayer()
            }
        }
    }

    /**
     * Gibt den MediaPlayer frei.
     */
    fun release() {
        Log.d(TAG, "🛑 Releasing SoundEffectPlayer")
        soundHandler.post {
            releaseMediaPlayer()
        }
        soundThread.quitSafely()
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing MediaPlayer", e)
            mediaPlayer = null
        }
    }
}

