package com.ronin.walkie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ronin.walkie.R

/**
 * Spielt kurze Soundeffekte (on.mp3 / off.mp3) ab, wie bei einem echten Walkie-Talkie.
 * - on.mp3: Wird abgespielt, wenn jemand anfängt zu senden (bei Sender + allen Zuhörern)
 * - off.mp3: Wird abgespielt, wenn jemand aufhört zu senden (bei Sender + allen Zuhörern)
 *
 * Verwendet MediaPlayer.create() für zuverlässige Ressourcen-Verwaltung.
 */
class SoundEffectPlayer {

    companion object {
        private const val TAG = "SoundEffectPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var context: Context? = null

    fun setContext(context: Context) {
        this.context = context
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
     * Nutzt MediaPlayer.create() für zuverlässige Initialisierung.
     * Stoppt vorherige Sounds, damit sie sich nicht überlagern.
     */
    private fun playSound(resId: Int) {
        try {
            // Vorherigen MediaPlayer stoppen und freigeben
            releaseMediaPlayer()

            val ctx = context ?: run {
                Log.w(TAG, "⚠️ Context not set, cannot play sound")
                return
            }

            // MediaPlayer muss auf dem Main-Thread ausgeführt werden
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    // MediaPlayer.create() ist zuverlässiger als manuelle Initialisierung
                    val mp = MediaPlayer.create(ctx, resId)
                    if (mp == null) {
                        Log.e(TAG, "❌ MediaPlayer.create() returned null for resource $resId")
                        return@post
                    }

                    // Audio-Attribute für Voice Communication setzen
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
                    Log.d(TAG, "▶️ Sound started playing")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error playing sound", e)
                    releaseMediaPlayer()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error posting to main thread", e)
        }
    }

    /**
     * Gibt den MediaPlayer frei.
     */
    fun release() {
        Log.d(TAG, "🛑 Releasing SoundEffectPlayer")
        releaseMediaPlayer()
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
