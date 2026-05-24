package com.ronin.walkie

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ronin.walkie.audio.AudioPlayer
import com.ronin.walkie.audio.AudioRecorder
import com.ronin.walkie.model.Channel
import com.ronin.walkie.network.WalkieWebSocketClient
import com.ronin.walkie.ui.channels.ChannelListScreen
import com.ronin.walkie.ui.login.LoginScreen
import com.ronin.walkie.ui.talk.TalkScreen
import com.ronin.walkie.ui.theme.WalkieTheme
import com.ronin.walkie.viewmodel.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var app: WalkieApplication
    private lateinit var webSocketClient: WalkieWebSocketClient
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 MainActivity.onCreate()")

        app = WalkieApplication.instance
        webSocketClient = app.webSocketClient
        audioPlayer = app.audioPlayer

        // Aktuelle Activity setzen (für AudioRecorder)
        app.setCurrentActivity(this)
        audioRecorder = app.audioRecorder

        enableEdgeToEdge()

        setContent {
            WalkieTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalkieApp(
                        webSocketClient = webSocketClient,
                        audioRecorder = audioRecorder,
                        audioPlayer = audioPlayer
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 MainActivity.onDestroy()")
        audioRecorder.stopRecording()
        audioPlayer.stopPlayback()
    }
}

@Composable
fun WalkieApp(
    webSocketClient: WalkieWebSocketClient,
    audioRecorder: AudioRecorder,
    audioPlayer: AudioPlayer
) {
    // Navigation State
    var currentScreen by remember { mutableStateOf("login") }
    var currentChannel by remember { mutableStateOf<Channel?>(null) }
    var currentUsername by remember { mutableStateOf("") }

    // ViewModels
    val loginViewModel: LoginViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(
                    application = WalkieApplication.instance,
                    webSocketClient = webSocketClient,
                    savedStateHandle = SavedStateHandle()
                ) as T
            }
        }
    )

    val channelListViewModel: ChannelListViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ChannelListViewModel(
                    application = WalkieApplication.instance,
                    webSocketClient = webSocketClient,
                    savedStateHandle = SavedStateHandle()
                ) as T
            }
        }
    )

    val channelViewModel: ChannelViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ChannelViewModel(
                    application = WalkieApplication.instance,
                    webSocketClient = webSocketClient,
                    audioRecorder = audioRecorder,
                    audioPlayer = audioPlayer,
                    soundEffectPlayer = WalkieApplication.instance.soundEffectPlayer,
                    username = currentUsername,
                    savedStateHandle = SavedStateHandle()
                ) as T
            }
        }
    )

    // LoginUiState beobachten
    val loginUiState by loginViewModel.uiState.collectAsState()

    // Bei erfolgreichem Login zur Channel-Liste navigieren
    LaunchedEffect(loginUiState.isLoggedIn) {
        if (loginUiState.isLoggedIn && currentScreen == "login") {
            currentUsername = loginUiState.username
            channelListViewModel.setUsername(loginUiState.username)
            currentScreen = "channels"
        }
    }

    // ChannelListUiState beobachten
    val channelListUiState by channelListViewModel.uiState.collectAsState()

    // ChannelViewModel UiState beobachten
    val talkUiState by channelViewModel.uiState.collectAsState()

    // Screen-Übergänge
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            when (targetState) {
                "login" -> slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                "channels" -> slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                "talk" -> fadeIn() togetherWith fadeOut()
                else -> fadeIn() togetherWith fadeOut()
            }
        },
        label = "screenTransition"
    ) { screen ->
        when (screen) {
            "login" -> {
                LoginScreen(
                    uiState = loginUiState,
                    onLogin = { username -> loginViewModel.login(username) },
                    onConnect = { loginViewModel.connect(WalkieApplication.SERVER_URL) },
                    onClearError = { loginViewModel.clearError() }
                )
            }

            "channels" -> {
                ChannelListScreen(
                    uiState = channelListUiState,
                    username = currentUsername,
                    onChannelClick = { channel ->
                        currentChannel = channel
                        channelViewModel.joinChannel(channel)
                        currentScreen = "talk"
                    },
                    onCreateChannel = { name, description, color ->
                        channelListViewModel.createChannel(name, description, color)
                    },
                    onRefresh = { channelListViewModel.loadChannels() },
                    onClearError = { channelListViewModel.clearError() }
                )
            }

            "talk" -> {
                TalkScreen(
                    uiState = talkUiState,
                    username = currentUsername,
                    onLeaveChannel = {
                        channelViewModel.leaveChannel()
                        currentChannel = null
                        currentScreen = "channels"
                    },
                    onStartTransmitting = { channelViewModel.startTransmitting() },
                    onStopTransmitting = { channelViewModel.stopTransmitting() },
                    onToggleTransmitting = { channelViewModel.toggleTransmitting() },
                    onToggleSpeaker = { channelViewModel.toggleSpeaker() }
                )
            }
        }
    }
}
