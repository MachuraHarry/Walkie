package com.ronin.walkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ronin.walkie.model.Channel
import com.ronin.walkie.ui.channels.ChannelListScreen
import com.ronin.walkie.ui.login.LoginScreen
import com.ronin.walkie.ui.talk.TalkScreen
import com.ronin.walkie.ui.theme.WalkieTheme
import com.ronin.walkie.viewmodel.ChannelListViewModel
import com.ronin.walkie.viewmodel.ChannelViewModel
import com.ronin.walkie.viewmodel.LoginViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Mikrofonberechtigung wird benötigt, um Sprache zu senden.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 MainActivity.onCreate()")
        enableEdgeToEdge()

        // Runtime-Berechtigungen anfragen (Android 6+)
        requestPermissionsIfNeeded()

        val app = application as WalkieApplication
        Log.d(TAG, "   WalkieApplication instance: $app")
        Log.d(TAG, "   SERVER_URL=${WalkieApplication.SERVER_URL}")
        Log.d(TAG, "   WebSocket client initialized=true (always initialized in WalkieApplication.onCreate())")
        
        // Aktuelle Activity setzen (für AudioRecorder)
        app.setCurrentActivity(this)

        setContent {
            WalkieTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalkieNavHost(app)
                }
            }
        }
        Log.d(TAG, "✅ MainActivity.onCreate() complete")
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "📋 Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d(TAG, "✅ All permissions already granted")
        }
    }
}

@Composable
fun WalkieNavHost(app: WalkieApplication) {
    val navController = rememberNavController()
    val webSocketClient = app.webSocketClient

    Log.d("MainActivity", "🏗️ WalkieNavHost composable")

    // ViewModels
    val loginViewModel: LoginViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("MainActivity", "🏗️ Creating LoginViewModel")
                return LoginViewModel(app, webSocketClient) as T
            }
        }
    )

    val channelListViewModel: ChannelListViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("MainActivity", "🏗️ Creating ChannelListViewModel")
                return ChannelListViewModel(app, webSocketClient) as T
            }
        }
    )

    NavHost(
        navController = navController,
        startDestination = "login",
        enterTransition = { fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) },
        exitTransition = { fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) }
    ) {
        composable("login") {
            Log.d("MainActivity", "📱 Login screen composable")
            val uiState by loginViewModel.uiState.collectAsState()

            LaunchedEffect(uiState.isLoggedIn) {
                Log.d("MainActivity", "LaunchedEffect: isLoggedIn=${uiState.isLoggedIn}")
                if (uiState.isLoggedIn) {
                    Log.d("MainActivity", "✅ User logged in, navigating to channels")
                    channelListViewModel.setUsername(uiState.username)
                    navController.navigate("channels") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            }

            LoginScreen(
                uiState = uiState,
                onLogin = { username ->
                    Log.d("MainActivity", "🔑 onLogin('$username') called")
                    loginViewModel.login(username)
                },
                onConnect = {
                    Log.d("MainActivity", "🔌 onConnect() called - connecting to server...")
                    Log.d("MainActivity", "   URL=${WalkieApplication.SERVER_URL}")
                    app.connectToServer()
                },
                onClearError = { loginViewModel.clearError() }
            )
        }

        composable(
            "channels",
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = androidx.compose.animation.core.tween(300)
                ) + fadeIn()
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = androidx.compose.animation.core.tween(300)
                ) + fadeOut()
            }
        ) {
            Log.d("MainActivity", "📱 Channels screen composable")
            val uiState by channelListViewModel.uiState.collectAsState()

            ChannelListScreen(
                uiState = uiState,
                onChannelSelected = { channel ->
                    Log.d("MainActivity", "📢 Channel selected: ${channel.id} '${channel.name}'")
                    navController.currentBackStackEntry?.savedStateHandle?.set("channelId", channel.id)
                    navController.currentBackStackEntry?.savedStateHandle?.set("channelName", channel.name)
                    navController.navigate("talk/${channel.id}")
                },
                onCreateChannel = { name, description, color ->
                    Log.d("MainActivity", "📢 onCreateChannel: name='$name'")
                    channelListViewModel.createChannel(name, description, color)
                },
                onRefresh = {
                    Log.d("MainActivity", "🔄 onRefresh called")
                    channelListViewModel.loadChannels()
                },
                onClearError = { channelListViewModel.clearError() }
            )
        }

        composable(
            "talk/{channelId}",
            enterTransition = {
                scaleIn(animationSpec = androidx.compose.animation.core.tween(300)) + fadeIn()
            },
            exitTransition = {
                scaleOut(animationSpec = androidx.compose.animation.core.tween(300)) + fadeOut()
            }
        ) { backStackEntry ->
            Log.d("MainActivity", "📱 Talk screen composable")
            val channelId = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<Int>("channelId") ?: backStackEntry.arguments?.getString("channelId")?.toIntOrNull() ?: 0
            val channelName = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("channelName") ?: "Channel $channelId"

            val channel = Channel(id = channelId, name = channelName)
            val loginUiState by loginViewModel.uiState.collectAsState()
            val username = loginUiState.username
            Log.d("MainActivity", "   Channel: ${channel.id} '${channel.name}', user: '$username'")

            val channelViewModel: ChannelViewModel = viewModel(
                key = "channel_$channelId",
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        Log.d("MainActivity", "🏗️ Creating ChannelViewModel for channel $channelId")
                        return ChannelViewModel(
                            app,
                            webSocketClient,
                            app.audioRecorder,
                            app.audioPlayer,
                            username
                        ) as T
                    }
                }
            )

            val uiState by channelViewModel.uiState.collectAsState()

            LaunchedEffect(channelId) {
                Log.d("MainActivity", "LaunchedEffect: joining channel $channelId")
                channelViewModel.joinChannel(channel)
            }

            TalkScreen(
                uiState = uiState,
                username = username,
                onLeaveChannel = {
                    Log.d("MainActivity", "🚪 onLeaveChannel called")
                    channelViewModel.leaveChannel()
                    navController.popBackStack("channels", inclusive = false)
                },
                onStartTransmitting = {
                    Log.d("MainActivity", "🔴 onStartTransmitting called")
                    channelViewModel.startTransmitting()
                },
                onStopTransmitting = {
                    Log.d("MainActivity", "🟢 onStopTransmitting called")
                    channelViewModel.stopTransmitting()
                },
                onToggleTransmitting = {
                    Log.d("MainActivity", "🔄 onToggleTransmitting called")
                    channelViewModel.toggleTransmitting()
                },
                onToggleSpeaker = {
                    Log.d("MainActivity", "🔊 onToggleSpeaker called")
                    channelViewModel.toggleSpeaker()
                }
            )
        }
    }
}
