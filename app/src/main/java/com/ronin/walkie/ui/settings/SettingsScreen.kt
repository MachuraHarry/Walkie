package com.ronin.walkie.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ronin.walkie.viewmodel.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onStartEditingUsername: () -> Unit,
    onCancelEditingUsername: () -> Unit,
    onUpdateUsername: (String) -> Unit,
    onSaveUsername: () -> Unit,
    onToggleSound: () -> Unit,
    onSetPttMode: (String) -> Unit,
    onSetAudioQuality: (Int) -> Unit,
    onToggleVad: () -> Unit,
    onSetVadThreshold: (Int) -> Unit,
    onStartEditingServerUrl: () -> Unit,
    onCancelEditingServerUrl: () -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onSaveServerUrl: () -> Unit,
    onSetDarkMode: (String) -> Unit,
    onToggleSpeakerDefault: () -> Unit,
    onToggleAudioCompression: () -> Unit,
    onSetPttToggleLockThreshold: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    onShowResetDialog: () -> Unit,
    onHideResetDialog: () -> Unit,
    onResetAllSettings: () -> Unit,
    onDismissRestartRequired: () -> Unit,
    onClearSavedMessage: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Reset-Dialog
    if (uiState.showResetDialog) {
        AlertDialog(
            onDismissRequest = onHideResetDialog,
            shape = RoundedCornerShape(24.dp),
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Einstellungen zurücksetzen?") },
            text = {
                Text("Alle Einstellungen werden auf die Standardwerte zurückgesetzt. Diese Aktion kann nicht rückgängig gemacht werden.")
            },
            confirmButton = {
                Button(
                    onClick = onResetAllSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Zurücksetzen")
                }
            },
            dismissButton = {
                TextButton(onClick = onHideResetDialog) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Restart-Hinweis
    if (uiState.showRestartRequired) {
        AlertDialog(
            onDismissRequest = onDismissRestartRequired,
            shape = RoundedCornerShape(24.dp),
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Neustart empfohlen") },
            text = {
                Text("Einige Einstellungen (wie die Audio-Qualität) werden erst nach einem Neustart der App wirksam. Bitte starte die App neu, um die Änderungen zu übernehmen.")
            },
            confirmButton = {
                TextButton(onClick = onDismissRestartRequired) {
                    Text("OK")
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
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Einstellungen", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            // Saved Message Banner
            AnimatedVisibility(
                visible = uiState.savedMessage != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                uiState.savedMessage?.let { message ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.startsWith("❌"))
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onClearSavedMessage,
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Schließen",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ===== PROFIL =====
            SectionHeader(
                icon = Icons.Default.Person,
                title = "Profil"
            )

            // Benutzername
            SettingsCard {
                if (uiState.isUsernameEditing) {
                    // Edit-Modus
                    UsernameEditContent(
                        username = uiState.username,
                        onUpdateUsername = onUpdateUsername,
                        onSave = onSaveUsername,
                        onCancel = onCancelEditingUsername
                    )
                } else {
                    // Anzeige-Modus
                    SettingsRow(
                        icon = Icons.Default.Badge,
                        title = "Benutzername",
                        subtitle = if (uiState.username.isNotEmpty()) uiState.username else "Nicht gesetzt",
                        onClick = onStartEditingUsername
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== AUDIO =====
            SectionHeader(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Audio"
            )

            SettingsCard {
                // Sound-Effekte
                SettingsSwitch(
                    icon = Icons.Default.MusicNote,
                    title = "Sound-Effekte (on/off)",
                    subtitle = "Walkie-Talkie Ein-/Aus-Sounds beim Senden",
                    checked = uiState.isSoundEnabled,
                    onCheckedChange = { onToggleSound() }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Audio-Qualität
                AudioQualitySelector(
                    selectedQuality = uiState.audioQuality,
                    onSelect = onSetAudioQuality
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Audio-Kompression
                SettingsSwitch(
                    icon = Icons.Default.Compress,
                    title = "Audio-Kompression",
                    subtitle = "Reduziert Datenverbrauch (experimentell)",
                    checked = uiState.isAudioCompressionEnabled,
                    onCheckedChange = { onToggleAudioCompression() }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Standard-Lautsprecher
                SettingsSwitch(
                    icon = Icons.Default.Speaker,
                    title = "Lautsprecher standardmäßig aktiv",
                    subtitle = "Beim Betreten eines Channels",
                    checked = uiState.isSpeakerDefault,
                    onCheckedChange = { onToggleSpeakerDefault() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== SPRECHEN (PTT) =====
            SectionHeader(
                icon = Icons.Default.Mic,
                title = "Sprechen (PTT)"
            )

            SettingsCard {
                // PTT Modus
                PttModeSelector(
                    selectedMode = uiState.pttMode,
                    onSelect = onSetPttMode
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // PTT Toggle Lock Threshold
                PttLockThresholdSlider(
                    threshold = uiState.pttToggleLockThreshold,
                    onThresholdChange = onSetPttToggleLockThreshold
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // VAD
                SettingsSwitch(
                    icon = Icons.Default.Hearing,
                    title = "Stille-Erkennung (VAD)",
                    subtitle = "Automatisches Stoppen bei Stille",
                    checked = uiState.isVadEnabled,
                    onCheckedChange = { onToggleVad() }
                )

                if (uiState.isVadEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // VAD Threshold
                    VadThresholdSlider(
                        threshold = uiState.vadThreshold,
                        onThresholdChange = onSetVadThreshold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== DESIGN =====
            SectionHeader(
                icon = Icons.Default.Palette,
                title = "Design"
            )

            SettingsCard {
                DarkModeSelector(
                    selectedMode = uiState.darkMode,
                    onSelect = onSetDarkMode
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== SPRACHE =====
            SectionHeader(
                icon = Icons.Default.Language,
                title = "Sprache"
            )

            SettingsCard {
                LanguageSelector(
                    selectedLanguage = uiState.language,
                    onSelect = onSetLanguage
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== SERVER =====
            SectionHeader(
                icon = Icons.Default.Dns,
                title = "Server"
            )

            SettingsCard {
                if (uiState.isServerUrlEditing) {
                    ServerUrlEditContent(
                        serverUrl = uiState.serverUrl,
                        onUpdateServerUrl = onUpdateServerUrl,
                        onSave = onSaveServerUrl,
                        onCancel = onCancelEditingServerUrl
                    )
                } else {
                    SettingsRow(
                        icon = Icons.Default.Link,
                        title = "Server-URL",
                        subtitle = if (uiState.serverUrl.isNotEmpty()) uiState.serverUrl else "Standard (App)",
                        onClick = onStartEditingServerUrl
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== INFO =====
            SectionHeader(
                icon = Icons.Default.Info,
                title = "Info"
            )

            SettingsCard {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "App-Version",
                    subtitle = "2.0.0",
                    onClick = {}
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsRow(
                    icon = Icons.Default.Code,
                    title = "Entwickelt von",
                    subtitle = "Ronin",
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== RESET BUTTON =====
            Button(
                onClick = onShowResetDialog,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Alle Einstellungen zurücksetzen",
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ===== HILFSKOMPONENTEN =====

@Composable
fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Bearbeiten",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SettingsSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun UsernameEditContent(
    username: String,
    onUpdateUsername: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            "Benutzername bearbeiten",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = onUpdateUsername,
            label = { Text("Dein Name") },
            placeholder = { Text("z.B. Max") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSave() }
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text("Abbrechen")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Speichern")
            }
        }
    }
}

@Composable
fun ServerUrlEditContent(
    serverUrl: String,
    onUpdateServerUrl: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            "Server-URL bearbeiten",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            "Nur ändern, wenn du einen eigenen Server verwendest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onUpdateServerUrl,
            label = { Text("ws://server:3000") },
            placeholder = { Text("ws://192.168.1.100:3000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSave() }
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text("Abbrechen")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Speichern")
            }
        }
    }
}

@Composable
fun AudioQualitySelector(
    selectedQuality: Int,
    onSelect: (Int) -> Unit
) {
    val qualities = listOf(
        8000 to "8 kHz (Telefonqualität)",
        16000 to "16 kHz (Standard)",
        32000 to "32 kHz (Hohe Qualität)",
        44100 to "44.1 kHz (CD-Qualität)"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "Audio-Qualität",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        qualities.forEach { (quality, label) ->
            val isSelected = quality == selectedQuality
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(quality) }
                    .padding(vertical = 6.dp, horizontal = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(quality) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                if (quality == 16000) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "(empfohlen)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
fun PttModeSelector(
    selectedMode: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "PTT-Modus",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Hold Mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect("hold") }
                .padding(vertical = 6.dp, horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectedMode == "hold",
                onClick = { onSelect("hold") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "Gedrückt halten",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedMode == "hold")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Senden nur solange der Button gedrückt wird",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Toggle Mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect("toggle") }
                .padding(vertical = 6.dp, horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectedMode == "toggle",
                onClick = { onSelect("toggle") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "Dauer-Senden (Toggle)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedMode == "toggle")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Einmal drücken zum Starten, erneut drücken zum Stoppen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PttLockThresholdSlider(
    threshold: Int,
    onThresholdChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Toggle-Einrastschwelle",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${threshold} dp - Nach oben ziehen zum Einrasten",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = threshold.toFloat(),
            onValueChange = { onThresholdChange(it.toInt()) },
            valueRange = 40f..150f,
            steps = 10,
            modifier = Modifier.padding(horizontal = 40.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Leicht",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Schwer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VadThresholdSlider(
    threshold: Int,
    onThresholdChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Hearing,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Stille-Empfindlichkeit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Schwellwert: $threshold",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = threshold.toFloat(),
            onValueChange = { onThresholdChange(it.toInt()) },
            valueRange = 100f..2000f,
            steps = 18,
            modifier = Modifier.padding(horizontal = 40.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Empfindlich",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Unempfindlich",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DarkModeSelector(
    selectedMode: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DarkMode,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "Erscheinungsbild",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val modes = listOf(
            "system" to "Wie System",
            "light" to "Hell",
            "dark" to "Dunkel"
        )

        modes.forEach { (mode, label) ->
            val isSelected = mode == selectedMode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) }
                    .padding(vertical = 6.dp, horizontal = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(mode) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (mode) {
                            "system" -> Icons.Default.SettingsBrightness
                            "light" -> Icons.Default.LightMode
                            "dark" -> Icons.Default.DarkMode
                            else -> Icons.Default.SettingsBrightness
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(
    selectedLanguage: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "Sprache",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val languages = listOf(
            "de" to "Deutsch",
            "en" to "English",
            "hr" to "Hrvatski",
            "nb" to "Norsk"
        )

        languages.forEach { (code, label) ->
            val isSelected = code == selectedLanguage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(code) }
                    .padding(vertical = 6.dp, horizontal = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(code) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (code == "de") Icons.Default.Flag else Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Sprachwechsel wird nach Neustart der App wirksam",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 40.dp)
        )
    }
}
