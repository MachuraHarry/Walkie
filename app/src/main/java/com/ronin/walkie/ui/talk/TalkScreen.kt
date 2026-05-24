package com.ronin.walkie.ui.talk

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronin.walkie.viewmodel.ConnectionQuality
import com.ronin.walkie.viewmodel.TalkUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(
    uiState: TalkUiState,
    username: String,
    onLeaveChannel: () -> Unit,
    onStartTransmitting: () -> Unit,
    onStopTransmitting: () -> Unit,
    onToggleTransmitting: () -> Unit,
    onToggleSpeaker: () -> Unit
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
                Column {
                    // Verbindungsqualität
                    ConnectionQualityBar(uiState.connectionQuality)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Verbindungsstatus
                        val connectionColor = when (uiState.connectionQuality) {
                            ConnectionQuality.GOOD -> Color(0xFF4CAF50)
                            ConnectionQuality.FAIR -> Color(0xFFFFC107)
                            ConnectionQuality.POOR -> Color(0xFFFF5722)
                            ConnectionQuality.DISCONNECTED -> Color(0xFFF44336)
                            ConnectionQuality.UNKNOWN -> Color(0xFF9E9E9E)
                        }
                        val connectionText = when {
                            uiState.isReconnecting -> "Wiederverbinde..."
                            uiState.connectionQuality == ConnectionQuality.DISCONNECTED -> "Getrennt"
                            uiState.connectionQuality == ConnectionQuality.POOR -> "Schlecht"
                            uiState.connectionQuality == ConnectionQuality.FAIR -> "Mittel"
                            uiState.connectionQuality == ConnectionQuality.GOOD -> "Verbunden"
                            else -> "Unbekannt"
                        }

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

                        // Kopfhörer-Status
                        if (uiState.isHeadsetPlugged) {
                            Icon(
                                Icons.Default.Headphones,
                                contentDescription = "Kopfhörer",
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF2196F3)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Kopfhörer",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2196F3)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        // Lautsprecher-Status in der Bottom-Bar
                        Icon(
                            if (uiState.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Lautsprecher",
                            modifier = Modifier.size(16.dp),
                            tint = if (uiState.isSpeakerOn) {
                                Color(0xFF4CAF50)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (uiState.isSpeakerOn) "Lautsprecher an" else "Lautsprecher aus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            // Error Snackbar
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(vertical = 8.dp),
                    action = {
                        TextButton(onClick = { /* clearError */ }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(error)
                }
            }

            // Mitgliederliste
            MemberListSection(
                users = uiState.users,
                talkingUsers = uiState.talkingUsers,
                currentUsername = username
            )

            Spacer(modifier = Modifier.weight(1f))

            // PTT Button + Lautsprecher-Button daneben
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PTTButton(
                    isTransmitting = uiState.isTransmitting,
                    isToggleMode = uiState.isToggleMode,
                    isConnected = uiState.isConnected,
                    onStartTransmitting = onStartTransmitting,
                    onStopTransmitting = onStopTransmitting,
                    onToggleTransmitting = onToggleTransmitting
                )

                // Lautsprecher-Button rechts neben dem PTT-Button
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                ) {
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (uiState.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Lautsprecher",
                            tint = if (uiState.isSpeakerOn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Toggle-Status Text
            if (uiState.isToggleMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "🔒 Dauer-Senden aktiv – Button nach unten ziehen zum Beenden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "👆 Gedrückt halten oder nach oben ziehen für Dauer-Modus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Zeigt die Verbindungsqualität als farbigen Balken an.
 */
@Composable
fun ConnectionQualityBar(quality: ConnectionQuality) {
    val color = when (quality) {
        ConnectionQuality.GOOD -> Color(0xFF4CAF50)
        ConnectionQuality.FAIR -> Color(0xFFFFC107)
        ConnectionQuality.POOR -> Color(0xFFFF5722)
        ConnectionQuality.DISCONNECTED -> Color(0xFFF44336)
        ConnectionQuality.UNKNOWN -> Color(0xFF9E9E9E)
    }

    val widthFraction = when (quality) {
        ConnectionQuality.GOOD -> 1f
        ConnectionQuality.FAIR -> 0.6f
        ConnectionQuality.POOR -> 0.3f
        ConnectionQuality.DISCONNECTED -> 0f
        ConnectionQuality.UNKNOWN -> 0.5f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(widthFraction)
                .background(color)
        )
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
            .fillMaxWidth(),
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
            // Status-Indikator mit grünem Border wenn sprechend
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.2f))
                    .then(
                        if (isTalking) Modifier.border(
                            3.dp,
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
    isConnected: Boolean,
    onStartTransmitting: () -> Unit,
    onStopTransmitting: () -> Unit,
    onToggleTransmitting: () -> Unit
) {
    // Lokaler Drag-Offset in Pixeln (wie weit wurde der Button nach oben/unten gezogen)
    var dragOffsetPx by remember { mutableStateOf(0f) }
    // Ob der Finger gerade auf dem Button ist
    var isFingerDown by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    // Schwellwert fürs Einrasten (80dp in Pixel)
    val lockThresholdPx = with(density) { 80.dp.toPx() }

    // Animationen
    val infiniteTransition = rememberInfiniteTransition(label = "ptt")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTransmitting || isToggleMode) 1.08f else 1f,
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

    // Ziel-Offset in dp für die Animation
    val targetOffsetDp = when {
        isToggleMode -> -80f // Eingerastet = 80dp nach oben
        isFingerDown -> with(density) { (dragOffsetPx / density.density).coerceIn(-80f, 0f) }
        else -> 0f // Zurückspringen
    }

    // Sanfte Animation des Drag-Offsets
    val animatedDragOffset by animateFloatAsState(
        targetValue = targetOffsetDp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dragOffset"
    )

    // Lock-Zone Alpha (wie nah am Einrasten)
    val lockZoneProgress = if (!isToggleMode && isFingerDown) {
        (-dragOffsetPx / lockThresholdPx).coerceIn(0f, 1f)
    } else if (isToggleMode) {
        1f
    } else {
        0f
    }

    val buttonColor = when {
        !isConnected -> Color(0xFF9E9E9E) // Grau wenn nicht verbunden
        isToggleMode -> Color(0xFFFF5722) // Orange für eingerastet
        isTransmitting -> Color(0xFFF44336) // Rot für Push-to-Talk
        else -> MaterialTheme.colorScheme.surfaceVariant // Grau für idle
    }

    val buttonIcon = when {
        !isConnected -> Icons.Default.WifiOff
        isToggleMode -> Icons.Default.Lock
        else -> Icons.Default.Mic
    }

    val buttonText = when {
        !isConnected -> "KEINE VERBINDUNG"
        isToggleMode -> "DAUER-SENDEN"
        isTransmitting -> "SENDET"
        else -> "HALTEN & SPRECHEN"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        // Schallwellen-Ringe
        if (isTransmitting || isToggleMode) {
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

        // Lock-Zone visuelles Feedback (Balken über dem Button)
        if (lockZoneProgress > 0f) {
            Box(
                modifier = Modifier
                    .offset(y = (animatedDragOffset - 130).dp)
                    .size(40.dp, (40 * lockZoneProgress).dp)
                    .alpha(lockZoneProgress)
                    .background(
                        Color(0xFFFF5722).copy(alpha = 0.3f * lockZoneProgress),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (lockZoneProgress > 0.7f) {
                    Text("🔒", fontSize = 16.sp)
                } else {
                    Text("⬆", fontSize = 16.sp, color = Color(0xFFFF5722))
                }
            }
        }

        // Haupt-PTT-Button
        Box(
            modifier = Modifier
                .offset(y = animatedDragOffset.dp)
                .size(180.dp)
                .scale(if (isTransmitting || isToggleMode) pulseScale else 1f)
                .pointerInput(isConnected, isToggleMode) {
                    // Endlosschleife: nach jeder Geste neu starten
                    while (true) {
                        // Niedrige Pointer-Ebene für sofortige Press-Reaktion
                        awaitPointerEventScope {
                            // Warte auf ersten Finger-Kontakt
                            val down = awaitPointerEvent()
                            val downChange = down.changes.firstOrNull() ?: return@awaitPointerEventScope
                            downChange.consume()

                            val downY = downChange.position.y

                            if (!isToggleMode && isConnected) {
                                // ✅ Finger runter → SOFORT senden starten
                                isFingerDown = true
                                dragOffsetPx = 0f
                                onStartTransmitting()
                            }

                            var hasLocked = false

                            // Verarbeite Bewegungen während Finger unten ist
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break

                                if (!change.pressed) break // Finger losgelassen

                                val currentY = change.position.y
                                val offsetY = currentY - downY

                                if (!isToggleMode && !hasLocked && isConnected) {
                                    // Drag nach oben verfolgen (negativ = nach oben)
                                    dragOffsetPx = offsetY.coerceAtMost(0f)

                                    // Prüfe auf Einrasten
                                    if (offsetY <= -lockThresholdPx) {
                                        hasLocked = true
                                        isFingerDown = false
                                        dragOffsetPx = 0f
                                        onStopTransmitting()
                                        onToggleTransmitting()
                                        change.consume()
                                        break
                                    }
                                } else if (isToggleMode) {
                                    // Im eingerasteten Modus: nach unten ziehen zum Lösen
                                    dragOffsetPx = offsetY.coerceAtLeast(0f)

                                    if (offsetY >= lockThresholdPx) {
                                        dragOffsetPx = 0f
                                        onToggleTransmitting()
                                        change.consume()
                                        break
                                    }
                                }

                                change.consume()
                            }

                            // Finger losgelassen ohne Einrasten
                            if (!isToggleMode && !hasLocked && isConnected) {
                                isFingerDown = false
                                dragOffsetPx = 0f
                                onStopTransmitting()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Hintergrund-Kreis
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(buttonColor)
            )

            // Inhalt
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    buttonIcon,
                    contentDescription = "PTT",
                    modifier = Modifier.size(48.dp),
                    tint = if (isTransmitting || isToggleMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    buttonText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = if (isTransmitting || isToggleMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
