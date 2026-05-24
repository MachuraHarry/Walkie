package com.ronin.walkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WalkieApplication

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
    }
}

@Composable
fun WalkieNavHost(app: WalkieApplication) {
    val navController = rememberNavController()
    val webSocketClient = app.webSocketClient

    // ViewModels
    val loginViewModel: LoginViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(app, webSocketClient) as T
            }
        }
    )

    val channelListViewModel: ChannelListViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
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
            val uiState by loginViewModel.uiState.collectAsState()

            LaunchedEffect(uiState.isLoggedIn) {
                if (uiState.isLoggedIn) {
                    channelListViewModel.setUsername(uiState.username)
                    navController.navigate("channels") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            }

            LoginScreen(
                uiState = uiState,
                onLogin = { username -> loginViewModel.login(username) },
                onConnect = { app.connectToServer() },
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
            val uiState by channelListViewModel.uiState.collectAsState()

            ChannelListScreen(
                uiState = uiState,
                onChannelSelected = { channel ->
                    navController.currentBackStackEntry?.savedStateHandle?.set("channel", channel)
                    navController.navigate("talk/${channel.id}")
                },
                onCreateChannel = { name, description, color ->
                    channelListViewModel.createChannel(name, description, color)
                },
                onRefresh = { channelListViewModel.loadChannels() },
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
            val context = LocalContext.current
            var hasPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasPermission = isGranted
                if (!isGranted) {
                    Toast.makeText(context, "Audio-Berechtigung wird für Walkie Talkie benötigt", Toast.LENGTH_LONG).show()
                }
            }

            LaunchedEffect(Unit) {
                if (!hasPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            val channel = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<Channel>("channel")

            if (channel != null && hasPermission) {
                val username = loginViewModel.uiState.value.username
                val signalingClient = app.signalingClient
                val webRTCManager = remember(username) {
                    app.createWebRTCManager(username)
                }

                val channelViewModel: ChannelViewModel = viewModel(
                    key = "channel_${channel.id}",
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return ChannelViewModel(
                                app,
                                webSocketClient,
                                signalingClient,
                                webRTCManager,
                                username
                            ) as T
                        }
                    }
                )

                val uiState by channelViewModel.uiState.collectAsState()

                LaunchedEffect(channel) {
                    channelViewModel.joinChannel(channel)
                }

                TalkScreen(
                    uiState = uiState,
                    username = username,
                    onLeaveChannel = {
                        channelViewModel.leaveChannel()
                        navController.popBackStack("channels", inclusive = false)
                    },
                    onStartTransmitting = { channelViewModel.startTransmitting() },
                    onStopTransmitting = { channelViewModel.stopTransmitting() },
                    onToggleTransmitting = { channelViewModel.toggleTransmitting() }
                )
            }
        }
    }
}
