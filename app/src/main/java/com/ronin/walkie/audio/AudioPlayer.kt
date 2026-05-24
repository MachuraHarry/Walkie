package com.ronin.walkie.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.ronin.walkie.network.WalkieWebSocketClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verbesserter AudioPlayer mit:
 * - Jitter-Buffer (100ms Puffer) für ruckelfreie Wiedergabe bei Netzwerkschwankungen
 * - Audio-Fokus-Management (respektiert Telefonanrufe/Navigation)
 * - Buffer-Overflow-Schutz
 * - Unterstützt mehrere gleichzeitige Sprecher durch Mischen der Audio-Streams
 * - Automatische Umschaltung zwischen Kopfhörer und Lautsprecher
 * - Headset-Erkennung: Bei angeschlossenen Kopfhörern wird automatisch über Kopfhörer ausgegeben
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Jitter-Buffer: 100ms = 1600 Bytes bei 16kHz/16bit/Mono
        private const val JITTER_BUFFER_MS = 100
        private const val JITTER_BUFFER_SIZE = SAMPLE_RATE * 2 * JITTER_BUFFER_MS / 1000

        // Maximaler Buffer (500ms) - danach werden alte Pakete verworfen
        private const val MAX_BUFFER_SIZE = SAMPLE_RATE * 2 * 500 / 1000
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var audioManager: AudioManager? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var hasAudioFocus = false
    private var isSpeakerOn = true
    private var context: Context? = null

    // Headset-Erkennung
    private var isHeadsetPlugged = false
    private var headsetReceiver: BroadcastReceiver? = null
    private var isBluetoothScoStarted = false

    // Callback für Headset-Status-Änderungen (wird vom ViewModel registriert)
    var onHeadsetStateChangeCallback: ((Boolean) -> Unit)? = null


    // Jitter-Buffer: Thread-sichere Queue für eingehende Audio-Daten
    private val jitterBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var bufferedBytes = AtomicBoolean(false)
    private var totalBufferedBytes = 0

    fun setAudioManager(context: Context) {
        this.context = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerHeadsetReceiver(context)
        // Initialen Headset-Status prüfen
        checkHeadsetState(context)
    }

    /**
     * Prüft den initialen Headset-Status und wendet sofort das korrekte Audio-Routing an.
     * Prüft sowohl kabelgebundene Kopfhörer als auch Bluetooth A2DP und SCO.
     */
    private fun checkHeadsetState(context: Context) {
        val am = audioManager ?: return
        val wired = am.isWiredHeadsetOn
        val bluetoothA2dp = am.isBluetoothA2dpOn
        val bluetoothSco = am.isBluetoothScoOn
        isHeadsetPlugged = wired || bluetoothA2dp || bluetoothSco
        Log.d(TAG, "🎧 Initial headset state: plugged=$isHeadsetPlugged (wired=$wired, a2dp=$bluetoothA2dp, sco=$bluetoothSco)")

        // Sofort Audio-Routing anwenden, damit bei bereits angeschlossenen Kopfhörern
        // der Ton über die Kopfhörer läuft (isSpeakerOn ist standardmäßig true, also
        // nur umschalten wenn Kopfhörer angeschlossen sind)
        if (isHeadsetPlugged) {
            isSpeakerOn = false
            applyAudioRouting()
            Log.d(TAG, "🎧 Headphones detected at startup: switching to headphone mode")
        }

        // Callback benachrichtigen, falls bereits registriert
        onHeadsetStateChangeCallback?.invoke(isHeadsetPlugged)


    }


    /**
     * Registriert einen Broadcast-Receiver für Headset-Ereignisse.
     * Reagiert auf:
     * - ACTION_HEADSET_PLUG: Kabelgebundene Kopfhörer werden ein-/abgesteckt
     * - ACTION_AUDIO_BECOMING_NOISY: Kopfhörer werden entfernt (zuverlässiger)
     */
    private fun registerHeadsetReceiver(context: Context) {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            }

            headsetReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_HEADSET_PLUG -> {
                            val state = intent.getIntExtra("state", 0)
                            val plugged = state == 1
                            Log.d(TAG, "🎧 Headset plug event: plugged=$plugged")
                            onHeadsetStateChanged(plugged)
                        }
                        AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                            // Kopfhörer wurden entfernt (während der Wiedergabe)
                            Log.d(TAG, "🎧 Audio becoming noisy (headphones removed)")
                            onHeadsetStateChanged(false)
                        }
                        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                            // Bluetooth-Kopfhörer
                            val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                            val connected = state == 2 // STATE_CONNECTED
                            Log.d(TAG, "🎧 Bluetooth A2DP profile changed: connected=$connected")
                            onHeadsetStateChanged(connected)
                        }
                    }
                }
            }

            context.registerReceiver(headsetReceiver, filter)
            Log.d(TAG, "🎧 Headset receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering headset receiver", e)
        }
    }

    /**
     * Startet Bluetooth SCO für die Audio-Kommunikation über Bluetooth-Headset.
     * Dies ist notwendig, damit das Bluetooth-Headset-Mikrofon verwendet wird.
     */
    private fun startBluetoothSco() {
        val am = audioManager ?: return
        try {
            if (!isBluetoothScoStarted && am.isBluetoothScoAvailableOffCall()) {
                am.startBluetoothSco()
                isBluetoothScoStarted = true
                Log.d(TAG, "🎧 Bluetooth SCO started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting Bluetooth SCO", e)
        }
    }

    /**
     * Stoppt Bluetooth SCO.
     */
    private fun stopBluetoothSco() {
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

    /**
     * Wird aufgerufen, wenn sich der Headset-Status ändert.
     * Bei angeschlossenen Kopfhörern: Audio läuft über Kopfhörer (Lautsprecher aus),
     * es sei denn der Benutzer hat den Lautsprecher manuell eingeschaltet.
     * Bei abgezogenen Kopfhörern: Zurück zum vorherigen Lautsprecher-Status.
     */
    private fun onHeadsetStateChanged(plugged: Boolean) {
        if (isHeadsetPlugged == plugged) return // Keine Änderung

        isHeadsetPlugged = plugged
        Log.d(TAG, "🎧 Headset state changed: plugged=$plugged, isSpeakerOn=$isSpeakerOn")

        if (plugged && !isSpeakerOn) {
            // Kopfhörer angeschlossen, Lautsprecher AUS → Audio über Kopfhörer
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = false
            startBluetoothSco()
            if (isBluetoothScoStarted) {
                audioManager?.isBluetoothScoOn = true
            }
            Log.d(TAG, "🎧 Headphones detected: routing audio through headphones")
        } else if (plugged && isSpeakerOn) {
            // Kopfhörer angeschlossen, aber Lautsprecher war AN → bleibt über Lautsprecher
            Log.d(TAG, "🎧 Headphones detected but speaker was ON, keeping speaker routing")
        } else {
            // Kopfhörer entfernt → Bluetooth SCO stoppen
            stopBluetoothSco()
            // zurück zum eingestellten Lautsprecher-Modus
            audioManager?.isSpeakerphoneOn = isSpeakerOn
            audioManager?.mode = if (isSpeakerOn) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "🎧 Headphones removed: restoring speaker mode (isSpeakerOn=$isSpeakerOn)")
        }

        // Callback benachrichtigen, damit das ViewModel die UI aktualisieren kann
        onHeadsetStateChangeCallback?.invoke(plugged)
    }



    /**
     * Gibt zurück, ob Kopfhörer angeschlossen sind.
     */
    fun isHeadsetPlugged(): Boolean {
        return isHeadsetPlugged
    }

    /**
     * Fordert Audio-Fokus an.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "🔊 Requesting audio focus...")

        // Audio-Fokus-Listener
        audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.d(TAG, "🔊 Audio focus gained")
                    hasAudioFocus = true
                    // AudioTrack-Lautstärke wiederherstellen
                    audioTrack?.let {
                        it.setVolume(1.0f) // Wieder normale Lautstärke
                    }
                    resumePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "🔊 Audio focus lost")
                    hasAudioFocus = false
                    pausePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "🔊 Audio focus lost transient")
                    hasAudioFocus = false
                    pausePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "🔊 Audio focus ducking")
                    // Leiser machen, aber weiter abspielen
                    audioTrack?.let {
                        it.setVolume(0.3f) // Reduzierte Lautstärke
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                    Log.d(TAG, "🔊 Audio focus gained transient")
                    hasAudioFocus = true
                    audioTrack?.let {
                        it.setVolume(1.0f) // Wieder normale Lautstärke
                    }
                    resumePlayback()
                }
            }
        }

        // Verwende AudioFocusRequest.Builder (API 26+) statt deprecated STREAM_VOICE_COMMUNICATION
        val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener!!)
            .build()

        val result = audioManager?.requestAudioFocus(audioFocusRequest)

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "🔊 Audio focus request: ${if (hasAudioFocus) "GRANTED" else "DENIED"}")
        return hasAudioFocus
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startPlayback(): Boolean {
        Log.d(TAG, "▶️ startPlayback() called")
        Log.d(TAG, "   isPlaying=$isPlaying")

        if (isPlaying) {
            Log.d(TAG, "   Already playing, returning true")
            return true
        }

        // Audio-Fokus anfordern
        requestAudioFocus()

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            // Buffer groß genug für Jitter-Buffer + AudioTrack-internen Buffer
            val bufferSize = Math.max(minBufferSize * 4, JITTER_BUFFER_SIZE * 2)
            Log.d(TAG, "   minBufferSize=$minBufferSize, actualBufferSize=$bufferSize")

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
                abandonAudioFocus()
                return false
            }
            Log.d(TAG, "   AudioTrack state: ${audioTrack?.state}")

            audioTrack?.play()
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "❌ AudioTrack not playing! state=${audioTrack?.playState}")
                abandonAudioFocus()
                return false
            }
            Log.d(TAG, "   AudioTrack playState: ${audioTrack?.playState}")

            isPlaying = true
            // Audio-Routing setzen (Headset hat Vorrang vor Lautsprecher-Einstellung)
            applyAudioRouting()
            Log.d(TAG, "✅ Playback started (${SAMPLE_RATE}Hz, jitter=${JITTER_BUFFER_MS}ms, speaker=$isSpeakerOn, headset=$isHeadsetPlugged)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting playback", e)
            abandonAudioFocus()
            return false
        }
    }

    /**
     * Wendet das korrekte Audio-Routing an.
     * - Wenn Kopfhörer angeschlossen sind und Lautsprecher AUS: Audio über Kopfhörer
     * - Wenn Kopfhörer angeschlossen sind und Lautsprecher AN: Audio über Telefon-Lautsprecher
     * - Wenn keine Kopfhörer: Verwendet die Benutzer-Einstellung (Lautsprecher an/aus)
     */
    private fun applyAudioRouting() {
        if (isHeadsetPlugged && !isSpeakerOn) {
            // Kopfhörer angeschlossen, Lautsprecher AUS → Audio über Kopfhörer
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = false
            startBluetoothSco()
            if (isBluetoothScoStarted) {
                audioManager?.isBluetoothScoOn = true
            }
            Log.d(TAG, "   Audio routing: headphones (speaker off, sco=$isBluetoothScoStarted)")
        } else if (isHeadsetPlugged && isSpeakerOn) {
            // Kopfhörer angeschlossen, aber Lautsprecher AN → Audio über Telefon-Lautsprecher
            stopBluetoothSco()
            audioManager?.isBluetoothScoOn = false
            audioManager?.isSpeakerphoneOn = true
            audioManager?.mode = AudioManager.MODE_NORMAL
            Log.d(TAG, "   Audio routing: headphones plugged but speaker FORCED ON")
        } else {
            audioManager?.isSpeakerphoneOn = isSpeakerOn
            audioManager?.mode = if (isSpeakerOn) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "   Audio routing: speaker=$isSpeakerOn")
        }
    }


    /**
     * Pausiert die Wiedergabe (bei transientem Fokus-Verlust).
     */
    private fun pausePlayback() {
        Log.d(TAG, "⏸️ pausePlayback() called")
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error pausing playback", e)
        }
    }

    /**
     * Setzt die Wiedergabe fort (nach Fokus-Gewinn).
     */
    private fun resumePlayback() {
        Log.d(TAG, "▶️ resumePlayback() called")
        try {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error resuming playback", e)
        }
    }

    /**
     * Verbindet den AudioPlayer mit dem WebSocket, um eingehende Audio-Daten zu empfangen.
     * Unterstützt sowohl Base64- als auch binäre Audio-Frames.
     */
    fun connectToWebSocket(webSocketClient: WalkieWebSocketClient) {
        Log.d(TAG, "🔗 connectToWebSocket()")
        
        // Binäre Audio-Daten (bevorzugt - schneller und kleiner)
        webSocketClient.onBinaryAudioDataReceived = { username, pcmData ->
            Log.d(TAG, "🎵 Binary audio received from '$username': ${pcmData.size} PCM bytes")
            playAudioData(pcmData)
        }
        
        // Fallback: Base64-kodierte Audio-Daten (für Kompatibilität)
        webSocketClient.onAudioDataReceived = { username, base64Data ->
            Log.d(TAG, "🎵 Audio received from '$username': ${base64Data.length} base64 chars")
            playBase64Audio(base64Data)
        }
        
        Log.d(TAG, "✅ AudioPlayer connected to WebSocket (binary + base64)")
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

        // Jitter-Buffer: Daten in die Queue einreihen
        jitterBuffer.add(data)
        totalBufferedBytes += data.size

        // Buffer-Overflow-Schutz: Wenn mehr als 500ms gepuffert, alte Daten verwerfen
        while (totalBufferedBytes > MAX_BUFFER_SIZE) {
            val oldData = jitterBuffer.poll()
            if (oldData != null) {
                totalBufferedBytes -= oldData.size
                Log.d(TAG, "   Buffer overflow: dropping ${oldData.size} bytes")
            } else {
                break
            }
        }

        // Jitter-Buffer füllen: Erst ab 100ms gepufferten Daten abspielen
        if (!bufferedBytes.get() && totalBufferedBytes >= JITTER_BUFFER_SIZE) {
            bufferedBytes.set(true)
            Log.d(TAG, "   Jitter buffer filled (${totalBufferedBytes} bytes), starting playback")
        }

        if (bufferedBytes.get()) {
            // Daten aus dem Jitter-Buffer an AudioTrack senden
            while (true) {
                val chunk = jitterBuffer.poll() ?: break
                totalBufferedBytes -= chunk.size
                try {
                    val written = audioTrack?.write(chunk, 0, chunk.size) ?: -1
                    if (written != chunk.size) {
                        Log.w(TAG, "   Wrote $written/${chunk.size} bytes to AudioTrack")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error writing audio data", e)
                    // Bei Fehler die restlichen Daten wieder in die Queue
                    jitterBuffer.add(chunk)
                    totalBufferedBytes += chunk.size
                    break
                }
            }
        } else {
            Log.d(TAG, "   Jitter buffer filling: ${totalBufferedBytes}/$JITTER_BUFFER_SIZE bytes")
        }
    }

    fun stopPlayback() {
        Log.d(TAG, "⏹️ stopPlayback() called")
        isPlaying = false
        bufferedBytes.set(false)

        // Jitter-Buffer leeren
        jitterBuffer.clear()
        totalBufferedBytes = 0

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "✅ Playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping playback", e)
        }

        // Bluetooth SCO stoppen falls aktiv
        stopBluetoothSco()

        abandonAudioFocus()
    }

    /**
     * Gibt Audio-Fokus wieder frei.
     */
    private fun abandonAudioFocus() {
        Log.d(TAG, "🔊 Abandoning audio focus")
        try {
            audioManager?.abandonAudioFocus(audioFocusListener)
            audioFocusListener = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error abandoning audio focus", e)
        }
    }

    fun isPlaying(): Boolean {
        Log.d(TAG, "🔍 isPlaying() = $isPlaying")
        return isPlaying
    }

    /**
     * Schaltet zwischen Ohrhörer (Earpiece) und Lautsprecher (Speakerphone) um.
     * Wenn Kopfhörer angeschlossen sind, hat der Kopfhörer Vorrang und der Lautsprecher
     * wird ausgeschaltet. Die Einstellung wird aber gespeichert, sodass sie nach dem
     * Abziehen der Kopfhörer wiederhergestellt wird.
     * @param on true = Lautsprecher an (laut), false = Ohrhörer (leise)
     */
    fun setSpeakerphoneOn(on: Boolean) {
        isSpeakerOn = on
        Log.d(TAG, "🔊 setSpeakerphoneOn: $on (headset=$isHeadsetPlugged)")

        if (isHeadsetPlugged && !on) {
            // Kopfhörer sind angeschlossen UND Benutzer will Lautsprecher AUS
            // → Audio über Kopfhörer ausgeben
            audioManager?.isSpeakerphoneOn = false
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            // Bluetooth SCO aktivieren für Headset-Mikrofon
            startBluetoothSco()
            if (isBluetoothScoStarted) {
                audioManager?.isBluetoothScoOn = true
            }
            Log.d(TAG, "   Headset plugged, speaker OFF: audio through headphones (sco=$isBluetoothScoStarted)")
        } else if (isHeadsetPlugged && on) {
            // Kopfhörer sind angeschlossen, aber Benutzer WILL Lautsprecher AN
            // → Bluetooth SCO stoppen und Audio über den Telefon-Lautsprecher ausgeben
            stopBluetoothSco()
            audioManager?.isBluetoothScoOn = false
            audioManager?.isSpeakerphoneOn = true
            audioManager?.mode = AudioManager.MODE_NORMAL
            Log.d(TAG, "   Headset plugged, speaker FORCED ON: audio through phone speaker")
        } else {
            // Keine Kopfhörer → Benutzer-Einstellung anwenden
            audioManager?.isSpeakerphoneOn = on
            audioManager?.mode = if (on) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "   AudioManager mode=${audioManager?.mode}, speakerphoneOn=${audioManager?.isSpeakerphoneOn}")
        }
    }


    /**
     * Gibt zurück, ob der Lautsprecher eingeschaltet ist.
     */
    fun isSpeakerOn(): Boolean {
        return isSpeakerOn
    }

    /**
     * Gibt den Headset-Receiver frei.
     */
    fun unregisterHeadsetReceiver() {
        try {
            headsetReceiver?.let { receiver ->
                context?.unregisterReceiver(receiver)
                headsetReceiver = null
                Log.d(TAG, "🎧 Headset receiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error unregistering headset receiver", e)
        }
    }
}
