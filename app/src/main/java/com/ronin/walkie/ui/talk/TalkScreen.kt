package com.ronin.walkie.ui.talk

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronin.walkie.viewmodel.TalkUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(
    uiState: TalkUiState,
    username: String,
    onLeaveChannel: () -> Unit,
    onStartTransmitting: () -> Unit,
    onStopTransmitting: () -> Unit,
    onToggleTransmitting: () -> Unit
) {
    var showLeaveDialog by remember { mutableStateOf(false) }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Channel verlassen?") },
            text = { Text("Bist du sicher, dass du den Channel verlassen möchtest?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveDialog = false
                        onLeaveChannel()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Verlassen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Radio,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            uiState.channel?.name ?: "Channel",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showLeaveDialog = true }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Verlassen"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Statusleiste
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Verbindungsstatus
                    val connectionColor = if (uiState.isConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                    val connectionText = if (uiState.isConnected) "Verbunden" else "Getrennt"
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(connectionColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        connectionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    if (uiState.isConnected) {
                        Text(
                            "Ping: ${uiState.ping}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Lautstärke",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mitgliederliste
            MemberListSection(
                users = uiState.users,
                talkingUsers = uiState.talkingUsers,
                currentUsername = username
            )

            Spacer(modifier = Modifier.weight(1f))

            // PTT Button
            PTTButton(
                isTransmitting = uiState.isTransmitting,
                isToggleMode = uiState.isToggleMode,
                onStartTransmitting = onStartTransmitting,
                onStopTransmitting = onStopTransmitting,
                onToggleTransmitting = onToggleTransmitting
            )

            // Toggle-Status Text
            if (uiState.isToggleMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "🔒 Dauer-Senden aktiv – Zum Beenden tippen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "👆 Gedrückt halten oder einmal tippen für Dauer-Modus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun MemberListSection(
    users: List<String>,
    talkingUsers: Set<String>,
    currentUsername: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Überschrift
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(
                "Mitglieder",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "(${users.size})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        if (users.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 200.dp)
            ) {
                items(users) { user ->
                    MemberItem(
                        username = user,
                        isTalking = talkingUsers.contains(user),
                        isSelf = user == currentUsername
                    )
                }
            }
        }
    }
}

@Composable
fun MemberItem(
    username: String,
    isTalking: Boolean,
    isSelf: Boolean
) {
    // Pulsierende Animation für sprechende Benutzer
    val infiniteTransition = rememberInfiniteTransition(label = "talking")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTalking) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val statusColor = when {
        isTalking && isSelf -> Color(0xFFF44336) // Rot für eigenes Senden
        isTalking -> Color(0xFF4CAF50) // Grün für andere Sprecher
        else -> Color(0xFF9E9E9E) // Grau für Zuhörer
    }

    val statusIcon = when {
        isTalking && isSelf -> Icons.Default.Mic
        isTalking -> Icons.Default.VolumeUp
        else -> Icons.Default.Person
    }

    val statusText = when {
        isTalking && isSelf -> "Du sendest"
        isTalking -> "spricht"
        else -> "hört zu"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isTalking) pulseScale else 1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTalking) {
                statusColor.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status-Indikator
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.2f))
                    .then(
                        if (isTalking) Modifier.border(
                            2.dp,
                            statusColor,
                            CircleShape
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = statusColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isSelf) "$username (Du)" else username,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }

            // Talking-Indikator (grüner Punkt)
            if (isTalking) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }
    }
}

@Composable
fun PTTButton(
    isTransmitting: Boolean,
    isToggleMode: Boolean,
    onStartTransmitting: () -> Unit,
    onStopTransmitting: () -> Unit,
    onToggleTransmitting: () -> Unit
) {
    // Animationen
    val infiniteTransition = rememberInfiniteTransition(label = "ptt")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTransmitting) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAlpha"
    )

    val buttonColor = when {
        isTransmitting && isToggleMode -> Color(0xFFFF5722) // Orange für Toggle
        isTransmitting -> Color(0xFFF44336) // Rot für gedrückt
        else -> MaterialTheme.colorScheme.surfaceVariant // Grau für idle
    }

    val buttonIcon = when {
        isTransmitting && isToggleMode -> Icons.Default.Lock
        isTransmitting -> Icons.Default.Mic
        else -> Icons.Default.Mic
    }

    val buttonText = when {
        isTransmitting && isToggleMode -> "DAUER-SENDEN"
        isTransmitting -> "SENDET"
        else -> "HALTEN & SPRECHEN"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        // Schallwellen-Ringe (nur beim Senden)
        if (isTransmitting) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(200.dp + (index * 50 * waveAlpha).dp)
                        .alpha(waveAlpha * (1f - index * 0.3f))
                        .background(
                            buttonColor.copy(alpha = 0.15f),
                            CircleShape
                        )
                )
            }
        }

        // Haupt-PTT-Button
        Button(
            onClick = {
                if (isTransmitting && isToggleMode) {
                    // Toggle ausschalten
                    onToggleTransmitting()
                } else if (!isTransmitting) {
                    // Einfacher Klick = Toggle starten
                    onToggleTransmitting()
                }
            },
            modifier = Modifier
                .size(180.dp)
                .scale(if (isTransmitting) pulseScale else 1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (!isToggleMode) {
                                onStartTransmitting()
                                tryAwaitRelease()
                                if (!isToggleMode) {
                                    onStopTransmitting()
                                }
                            }
                        }
                    )
                },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = if (isTransmitting) 12.dp else 4.dp,
                pressedElevation = 8.dp
            )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    buttonIcon,
                    contentDescription = "PTT",
                    modifier = Modifier.size(48.dp),
                    tint = if (isTransmitting) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    buttonText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = if (isTransmitting) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
