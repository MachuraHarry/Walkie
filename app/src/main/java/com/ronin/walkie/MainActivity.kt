package com.ronin.walkie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import com.ronin.walkie.settings.LocaleHelper
import com.ronin.walkie.settings.SettingsManager
import com.ronin.walkie.ui.channels.ChannelListScreen
import com.ronin.walkie.ui.login.LoginScreen
import com.ronin.walkie.ui.settings.SettingsScreen
import com.ronin.walkie.ui.talk.TalkScreen
import com.ronin.walkie.ui.theme.WalkieTheme
import com.ronin.walkie.ui.theme.isDarkThemeFromSettings
import com.ronin.walkie.viewmodel.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var app: WalkieApplication
    private lateinit var webSocketClient: WalkieWebSocketClient
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer

    override fun attachBaseContext(newBase: Context) {
        val settingsManager = SettingsManager(newBase)
        val language = settingsManager.getLanguage()
        val context = LocaleHelper.setLocale(newBase, language)
        super.attachBaseContext(context)
        Log.d(TAG, "🌐 Applied locale in attachBaseContext: $language")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 MainActivity.onCreate()")

        app = WalkieApplication.instance
        webSocketClient = app.webSocketClient
        audioPlayer = app.audioPlayer

        // Aktuelle Activity setzen (für AudioRecorder)
        app.setCurrentActivity(this)
        audioRecorder = app.audioRecorder

        // POST_NOTIFICATIONS Permission anfordern (ab Android 13)
        // Notwendig, damit die Foreground Service Notification angezeigt wird
        requestNotificationPermission()

        // RECORD_AUDIO Permission anfordern (ab Android 16 / targetSDK 36)
        // Notwendig, damit der Foreground Service mit Typ "microphone" gestartet werden kann
        requestRecordAudioPermission()

        enableEdgeToEdge()

        setContent {
            // DarkMode aus den Einstellungen lesen
            val isDark = isDarkThemeFromSettings(this)
            WalkieTheme(darkTheme = isDark) {
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

    /**
     * Fordert die POST_NOTIFICATIONS-Berechtigung an (ab Android 13 / API 33).
     * Ohne diese Berechtigung wird die Foreground Service Notification nicht angezeigt.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "📋 Requesting POST_NOTIFICATIONS permission")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            } else {
                Log.d(TAG, "✅ POST_NOTIFICATIONS already granted")
            }
        }
    }

    /**
     * Fordert die RECORD_AUDIO-Berechtigung an.
     * Ab Android 16 (targetSDK 36) wird diese Berechtigung benötigt, um einen
     * Foreground Service mit dem Typ "microphone" starten zu können.
     * Ohne diese Berechtigung wirft startForeground() einen SecurityException.
     */
    private fun requestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "📋 Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1003
            )
        } else {
            Log.d(TAG, "✅ RECORD_AUDIO already granted")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "⏸️ MainActivity.onStop() - App geht in den Hintergrund")
        // Audio-Playback pausieren (wird bei onStart() neu gestartet)
        // ABER: Aufnahme NICHT stoppen, wenn PTT aktiv ist!
        // Der Foreground Service hält die WebSocket-Verbindung und Audio-Ressourcen am Leben.
        if (!audioRecorder.isRecording()) {
            audioPlayer.stopPlayback()
        } else {
            Log.d(TAG, "   PTT ist aktiv - Aufnahme läuft im Hintergrund weiter!")
            // Playback trotzdem pausieren (wir hören ja eh nichts, wenn wir selbst senden)
            audioPlayer.stopPlayback()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "▶️ MainActivity.onStart() - App kommt in den Vordergrund")
        // Audio-Playback neu starten, wenn wir in einem Channel sind
        // (die WebSocket-Callbacks sind noch registriert)
        if (!audioPlayer.isPlaying()) {
            audioPlayer.startPlayback()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 MainActivity.onDestroy()")
        // Foreground Service stoppen, wenn die Activity endgültig zerstört wird
        app.stopForegroundService()
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

    // SettingsManager (für den Zugriff auf gespeicherte Einstellungen)
    val settingsManager = remember { SettingsManager(WalkieApplication.instance) }

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

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    application = WalkieApplication.instance
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

    // SettingsUiState beobachten
    val settingsUiState by settingsViewModel.uiState.collectAsState()

    // Screen-Übergänge
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            when (targetState) {
                "login" -> slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                "channels" -> slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                "talk" -> fadeIn() togetherWith fadeOut()
                "settings" -> slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
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
                    onChannelClickWithPassword = { channel, password ->
                        currentChannel = channel
                        channelViewModel.joinChannel(channel, password)
                        currentScreen = "talk"
                    },
                    onCreateChannel = { name, description, color, password ->
                        channelListViewModel.createChannel(name, description, color, password)
                    },
                    onDeleteChannel = { channel ->
                        channelListViewModel.deleteChannel(channel.id)
                    },
                    onRefresh = { channelListViewModel.loadChannels() },
                    onClearError = { channelListViewModel.clearError() },
                    onSettingsClick = { currentScreen = "settings" }
                )
            }

            "talk" -> {
                // Foreground Service starten, sobald wir im Talk-Screen sind
                LaunchedEffect(currentChannel) {
                    if (currentChannel != null) {
                        WalkieApplication.instance.startForegroundService(currentChannel!!.name)
                    }
                }

                TalkScreen(
                    uiState = talkUiState,
                    username = currentUsername,
                    onLeaveChannel = {
                        // Foreground Service stoppen beim Verlassen des Channels
                        WalkieApplication.instance.stopForegroundService()
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

            "settings" -> {
                SettingsScreen(
                    uiState = settingsUiState,
                    onBack = { currentScreen = "channels" },
                    onStartEditingUsername = { settingsViewModel.startEditingUsername() },
                    onCancelEditingUsername = { settingsViewModel.cancelEditingUsername() },
                    onUpdateUsername = { settingsViewModel.updateUsername(it) },
                    onSaveUsername = { settingsViewModel.saveUsername() },
                    onToggleSound = { settingsViewModel.toggleSound() },
                    onSetPttMode = { settingsViewModel.setPttMode(it) },
                    onSetAudioQuality = { settingsViewModel.setAudioQuality(it) },
                    onToggleVad = { settingsViewModel.toggleVad() },
                    onSetVadThreshold = { settingsViewModel.setVadThreshold(it) },
                    onStartEditingServerUrl = { settingsViewModel.startEditingServerUrl() },
                    onCancelEditingServerUrl = { settingsViewModel.cancelEditingServerUrl() },
                    onUpdateServerUrl = { settingsViewModel.updateServerUrl(it) },
                    onSaveServerUrl = { settingsViewModel.saveServerUrl() },
                    onSetDarkMode = { settingsViewModel.setDarkMode(it) },
                    onToggleSpeakerDefault = { settingsViewModel.toggleSpeakerDefault() },
                    onToggleAudioCompression = { settingsViewModel.toggleAudioCompression() },
                    onSetPttToggleLockThreshold = { settingsViewModel.setPttToggleLockThreshold(it) },
                    onSetLanguage = { settingsViewModel.setLanguage(it) },
                    onShowResetDialog = { settingsViewModel.showResetDialog() },
                    onHideResetDialog = { settingsViewModel.hideResetDialog() },
                    onResetAllSettings = { settingsViewModel.resetAllSettings() },
                    onDismissRestartRequired = { settingsViewModel.dismissRestartRequired() },
                    onClearSavedMessage = { settingsViewModel.clearSavedMessage() }
                )
            }
        }
    }
}
