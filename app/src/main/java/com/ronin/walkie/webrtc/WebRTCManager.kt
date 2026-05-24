package com.ronin.walkie.webrtc

import android.content.Context
import android.util.Log
import com.ronin.walkie.network.SignalingClient
import org.webrtc.*
import java.util.*

class WebRTCManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val username: String
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peers = mutableMapOf<String, PeerConnection>()
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var eglBase: EglBase? = null

    // Callback für eingehende Audiodaten
    var onRemoteAudioStarted: ((String) -> Unit)? = null
    var onRemoteAudioStopped: ((String) -> Unit)? = null

    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        // Create PeerConnectionFactory
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        // Create audio source and track
        audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)

        Log.d(TAG, "WebRTC initialized")
    }

    fun createPeerConnection(peerId: String, channelId: Int) {
        if (peers.containsKey(peerId)) {
            Log.w(TAG, "Peer connection already exists for $peerId")
            return
        }

        val iceServers = listOf(
            PeerConnection.IceServer.builder(STUN_SERVER).createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE candidate for $peerId")
                signalingClient.sendIceCandidate(
                    mapOf("sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex, "candidate" to candidate.sdp),
                    peerId,
                    channelId
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state for $peerId: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state for $peerId: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.d(TAG, "Connected to $peerId")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from $peerId")
                        onRemoteAudioStopped?.invoke(peerId)
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering state for $peerId: $state")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream added from $peerId")
                if (stream.audioTracks.isNotEmpty()) {
                    onRemoteAudioStarted?.invoke(peerId)
                }
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream removed from $peerId")
                onRemoteAudioStopped?.invoke(peerId)
            }

            override fun onDataChannel(channel: DataChannel) {}

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed for $peerId")
            }

            override fun onAddTrack(track: RtpReceiver, streams: Array<out MediaStream>) {
                Log.d(TAG, "Remote track added from $peerId")
                if (track.track()?.kind() == "audio") {
                    onRemoteAudioStarted?.invoke(peerId)
                }
            }
        })

        if (peerConnection != null) {
            peers[peerId] = peerConnection

            // Add local audio track
            val audioStream = peerConnectionFactory?.createLocalMediaStream("local_stream")
            localAudioTrack?.let { audioStream?.addTrack(it) }
            audioStream?.let { peerConnection.addStream(it) }

            Log.d(TAG, "Peer connection created for $peerId")
        }
    }

    fun createOffer(peerId: String, channelId: Int) {
        val peerConnection = peers[peerId] ?: return

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set for $peerId")
                        signalingClient.sendOffer(
                            mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description),
                            peerId,
                            channelId
                        )
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    fun handleOffer(offer: Map<String, Any>, from: String, channelId: Int) {
        val peerConnection = peers[from] ?: run {
            createPeerConnection(from, channelId)
            peers[from] ?: return
        }

        val sdp = SessionDescription(
            SessionDescription.Type.OFFER,
            offer["sdp"] as? String ?: return
        )

        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set for $from")
                createAnswer(from, channelId)
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sdp)
    }

    private fun createAnswer(peerId: String, channelId: Int) {
        val peerConnection = peers[peerId] ?: return

        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer set for $peerId")
                        signalingClient.sendAnswer(
                            mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description),
                            peerId,
                            channelId
                        )
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local answer: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create answer: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    fun handleAnswer(answer: Map<String, Any>, from: String) {
        val peerConnection = peers[from] ?: return

        val sdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            answer["sdp"] as? String ?: return
        )

        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set for $from")
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set remote answer: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sdp)
    }

    fun handleIceCandidate(candidate: Map<String, Any>, from: String) {
        val peerConnection = peers[from] ?: return

        val iceCandidate = IceCandidate(
            candidate["sdpMid"] as? String ?: "",
            (candidate["sdpMLineIndex"] as? Double)?.toInt() ?: 0,
            candidate["candidate"] as? String ?: ""
        )

        peerConnection.addIceCandidate(iceCandidate)
    }

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Audio ${if (enabled) "enabled" else "disabled"}")
    }

    fun disconnectAll() {
        Log.d(TAG, "Disconnecting all peers")
        for ((peerId, connection) in peers) {
            connection.close()
        }
        peers.clear()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }

    fun disconnectPeer(peerId: String) {
        peers[peerId]?.close()
        peers.remove(peerId)
    }
}
