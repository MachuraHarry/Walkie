# Walkie Talkie App – Gesamtarchitektur & Entwicklungsplan

## 1. Überblick

Die **Walkie Talkie App** ermöglicht Echtzeit-Sprachkommunikation über das Internet.  
Benutzer loggen sich mit einem Namen ein, treten Kanälen (Channels) bei und kommunizieren per **Push-to-Talk (PTT)**.

### Kernfunktionen
- **Login** mit Benutzername (kein Passwort nötig)
- **Channels** erstellen und beitreten
- **Push-to-Talk**: Gedrückt halten = senden; einmal klicken = Dauer-Senden (Toggle)
- **Echtzeit-Audio-Streaming** zwischen allen Channel-Mitgliedern
- **Server** verwaltet Channels, Nutzer und signalisiert WebRTC-Verbindungen

---

## 2. Technologie-Stack

| Komponente | Technologie | Begründung |
|---|---|---|
| **Android Client** | Kotlin + Jetpack Compose | Modernes UI-Toolkit, bereits vorhanden |
| **Audio-Capture** | Android AudioRecord (MediaRecorder) | Low-Level-Zugriff auf Mikrofon |
| **Audio-Wiedergabe** | Android AudioTrack | Low-Latency-Wiedergabe |
| **Netzwerk (Signalisierung)** | WebSocket (OkHttp) | Bidirektionale Echtzeit-Kommunikation |
| **Netzwerk (Audio)** | WebRTC (google-webrtc) | Low-Latency Peer-to-Peer Audio |
| **Server** | Node.js + TypeScript | Ideal für WebSocket/WebRTC-Signalisierung |
| **Server-Framework** | Express + ws (WebSocket) | Leichtgewichtig, bewährt |
| **Datenbank** | PostgreSQL | Channels, Nutzer, Nachrichten-Log |
| **Containerisierung** | Docker + Docker Compose | Einfaches Deployment |
| **Audio-Codec** | Opus (über WebRTC) | Beste Qualität bei niedriger Latenz |

---

## 3. Systemarchitektur

```
┌─────────────────────────────────────────────────────────────┐
│                        Docker Host                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Docker Compose                          │   │
│  │  ┌──────────────┐    ┌──────────────┐               │   │
│  │  │  Node.js      │    │  PostgreSQL  │               │   │
│  │  │  Server       │◄──►│  (Datenbank) │               │   │
│  │  │  (WebSocket)  │    └──────────────┘               │   │
│  │  │  Port 3000    │                                   │   │
│  │  └──────┬───────┘                                   │   │
│  └─────────┼────────────────────────────────────────────┘   │
└────────────┼────────────────────────────────────────────────┘
             │ WebSocket (Signalisierung)
             │ WebRTC (Audio-Direktverbindung)
    ┌────────┴────────┐     ┌────────┴────────┐
    │  Android Client │◄───►│  Android Client │
    │  (Benutzer A)   │     │  (Benutzer B)   │
    └─────────────────┘     └─────────────────┘
```

### Kommunikationsablauf

1. **Client → Server (WebSocket):** Login, Channel beitreten/verlassen, SDP-Angebote/Antworten, ICE-Candidates
2. **Client ↔ Client (WebRTC):** Direkte Audio-Übertragung nach erfolgreichem Signaling
3. **Server → Client (WebSocket):** Nutzerliste, Channel-Liste, eingehende SDP/ICE

---

## 4. Datenbank-Schema (PostgreSQL)

```sql
-- Tabelle: users
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    joined_at TIMESTAMP DEFAULT NOW(),
    last_active TIMESTAMP DEFAULT NOW()
);

-- Tabelle: channels
CREATE TABLE channels (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    created_by VARCHAR(50) REFERENCES users(username),
    created_at TIMESTAMP DEFAULT NOW(),
    is_active BOOLEAN DEFAULT TRUE
);

-- Tabelle: channel_members
CREATE TABLE channel_members (
    id SERIAL PRIMARY KEY,
    channel_id INTEGER REFERENCES channels(id) ON DELETE CASCADE,
    username VARCHAR(50) REFERENCES users(username) ON DELETE CASCADE,
    joined_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(channel_id, username)
);
```

---

## 5. Server-Architektur (Node.js + TypeScript)

### Projektstruktur (Server)
```
server/
├── Dockerfile
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts              # Einstiegspunkt
│   ├── config.ts             # Konfiguration (Port, DB, etc.)
│   ├── database.ts           # PostgreSQL-Verbindung (pg)
│   ├── websocket/
│   │   ├── handler.ts        # WebSocket-Nachrichtenverarbeitung
│   │   └── types.ts          # Nachrichtentypen
│   ├── services/
│   │   ├── userService.ts    # Nutzerverwaltung
│   │   ├── channelService.ts # Channel-Verwaltung
│   │   └── signalingService.ts # WebRTC-Signalisierung
│   └── models/
│       ├── User.ts
│       └── Channel.ts
└── docker-compose.yml
```

### WebSocket-Nachrichtenprotokoll

```typescript
// Nachricht vom Client zum Server
interface ClientMessage {
  type: "login" | "create_channel" | "join_channel" | "leave_channel" |
        "get_channels" | "get_users" | "signal";
  payload: any;
}

// Nachricht vom Server zum Client
interface ServerMessage {
  type: "login_success" | "login_error" | "channel_created" | "channel_list" |
        "user_joined" | "user_left" | "user_list" | "signal" | "error";
  payload: any;
}

// Signal-Nachricht für WebRTC
interface SignalMessage {
  type: "offer" | "answer" | "ice_candidate";
  from: string;
  to: string;
  channelId: number;
  data: any; // SDP oder ICE-Candidate
}
```

### Server-Funktionen

| Funktion | Beschreibung |
|---|---|
| `login(username)` | Prüft/erstellt Benutzer, sendet `login_success` |
| `create_channel(name, creator)` | Erstellt Channel, setzt Ersteller als Member |
| `join_channel(channelId, username)` | Fügt Nutzer zu Channel hinzu, benachrichtigt alle |
| `leave_channel(channelId, username)` | Entfernt Nutzer, benachrichtigt alle |
| `get_channels()` | Gibt Liste aller aktiven Channels zurück |
| `get_users(channelId)` | Gibt Liste aller Nutzer in einem Channel zurück |
| `signal(data)` | Leitet WebRTC-Signale an Zielnutzer weiter |

---

## 6. Android-Client-Architektur

### Projektstruktur (App)
```
app/src/main/java/com/ronin/walkie/
├── MainActivity.kt
├── WalkieApplication.kt
├── audio/
│   ├── AudioRecorder.kt       # Mikrofon-Aufnahme
│   └── AudioPlayer.kt         # Audio-Wiedergabe
├── network/
│   ├── WebSocketClient.kt     # WebSocket-Verbindung
│   └── SignalingClient.kt     # WebRTC-Signaling-Logik
├── webrtc/
│   └── WebRTCManager.kt       # WebRTC-PeerConnection-Verwaltung
├── model/
│   ├── User.kt
│   ├── Channel.kt
│   └── Message.kt
├── viewmodel/
│   ├── LoginViewModel.kt
│   ├── ChannelListViewModel.kt
│   └── ChannelViewModel.kt
└── ui/
    ├── login/
    │   └── LoginScreen.kt
    ├── channels/
    │   ├── ChannelListScreen.kt
    │   └── CreateChannelDialog.kt
    └── talk/
        └── TalkScreen.kt       # Push-to-Talk UI
```

### Abhängigkeiten (app/build.gradle.kts)

```kotlin
dependencies {
    // Bestehende Dependencies bleiben
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.webrtc:google-webrtc:1.0.32006")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
}
```

### UI/UX Design – Modernes Walkie-Talkie Erlebnis

Die App folgt einem **modernen, dunklen Material-3 Design** mit kräftigen Akzentfarben, das an professionelle Funkgeräte und moderne Kommunikations-Apps (wie Discord, Telegram) angelehnt ist.

#### Farbpalette & Theme

```kotlin
// Color.kt – Erweiterung des bestehenden Themes
// Dark Theme als Standard (für Walkie-Talkie typisch)
val WalkieDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),           // Grün – für aktive/sendende Zustände
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1B5E20),  // Dunkelgrün
    secondary = Color(0xFF2196F3),         // Blau – für Interaktionen
    tertiary = Color(0xFFFF5722),          // Orange – Warnungen/Hinweise
    background = Color(0xFF121212),        // Tiefschwarz
    surface = Color(0xFF1E1E1E),           // Dunkelgrau für Karten
    surfaceVariant = Color(0xFF2C2C2C),    // Hellgrau für Container
    onBackground = Color(0xFFE0E0E0),      // Helles Grau für Text
    onSurface = Color(0xFFE0E0E0),
    error = Color(0xFFCF6679),             // Rot für Fehler/Verbindungsabbruch
)

// Light Theme (optional, für helle Umgebungen)
val WalkieLightColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),           // Dunkelgrün
    secondary = Color(0xFF1976D2),         // Dunkelblau
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    // ...
)
```

#### Typografie

```kotlin
// Moderne, klare Schriftarten
// Überschriften: SansSerif, fett, groß
// Channel-Name: 22sp, Bold
// Mitgliedername: 16sp, Medium
// PTT-Button-Text: 18sp, Bold, Großbuchstaben
// Status-Text: 14sp, Regular
```

---

#### 1. LoginScreen – Willkommensbildschirm

```
┌─────────────────────────────────────┐
│                                     │
│          📡 Walkie Talkie           │
│         (App-Logo / Icon)           │
│                                     │
│    ┌─────────────────────────┐      │
│    │  Dein Name              │      │
│    └─────────────────────────┘      │
│                                     │
│    ╔═══════════════════════════╗    │
│    ║     🔗 Channel beitreten  ║    │
│    ╚═══════════════════════════╝    │
│                                     │
│         Version 1.0                 │
└─────────────────────────────────────┘
```

**Design-Details:**
- **Zentrierter Inhalt** mit großem App-Icon (Walkie-Talkie-Symbol) oben
- **App-Name** "Walkie Talkie" in großer, fetter Schrift
- **Textfeld** "Dein Name" mit modernem Outlined-Design, Platzhaltertext, Fokus-Farbe in Blau
- **"Channel beitreten"-Button** – groß, abgerundet (24dp Radius), primäre Farbe (Grün), mit Icon
- **Eingabevalidierung**: Button ist deaktiviert (grau) wenn das Namensfeld leer ist oder weniger als 2 Zeichen hat
- **Animation**: Sanftes Einblenden der Elemente beim Öffnen
- **Fehleranzeige**: Roter Text unter dem Feld bei ungültiger Eingabe

**Compose-Code-Struktur:**
```kotlin
@Composable
fun LoginScreen(
    onLogin: (String) -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var username by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Icon
        Icon(
            imageVector = Icons.Default.Radio,
            contentDescription = "Walkie Talkie",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        // App Name
        Text(
            text = "Walkie Talkie",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(48.dp))
        
        // Username Input
        OutlinedTextField(
            value = username,
            onValueChange = { 
                username = it
                isError = false
            },
            label = { Text("Dein Name") },
            singleLine = true,
            isError = isError,
            supportingText = if (isError) {
                { Text("Name muss mindestens 2 Zeichen haben") }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        // Join Button
        Button(
            onClick = {
                if (username.length >= 2) onLogin(username)
                else isError = true
            },
            enabled = username.length >= 2,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Channel beitreten", style = MaterialTheme.typography.titleMedium)
        }
    }
}
```

---

#### 2. ChannelListScreen – Channel-Übersicht

```
┌─────────────────────────────────────┐
│  📡 Walkie Talkie          👤 Name  │
│─────────────────────────────────────│
│                                     │
│  ┌─────────────────────────────┐    │
│  │  🔴 Allgemein         3 👥  │    │
│  │  Öffentlicher Chat-Raum     │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  🟢 Gaming             5 👥  │    │
│  │  Zocker unter sich          │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  🔵 Arbeit              2 👥  │    │
│  │  Team-Meetings               │    │
│  └─────────────────────────────┘    │
│                                     │
│         [ + Channel erstellen ]     │
└─────────────────────────────────────┘
```

**Design-Details:**
- **Top-Bar**: App-Name links, aktueller Benutzername rechts (mit Avatar-Icon)
- **Channel-Karten**: Modernes Card-Design mit:
  - **Farbiger Punkt** links (Channel-Farbe, zufällig oder vom Ersteller gewählt)
  - **Channel-Name** fett und groß
  - **Mitgliederanzahl** mit 👥-Icon rechts oben
  - **Kurzbeschreibung** in kleinerer, grauer Schrift
  - **Abgerundete Ecken** (16dp), leichter Schatten
- **Swipe-to-Refresh**: Zum Aktualisieren der Channel-Liste
- **FloatingActionButton**: Unten rechts zum Erstellen eines neuen Channels
- **Leerer Zustand**: Wenn keine Channels existieren, wird eine freundliche Nachricht + Illustration angezeigt

**CreateChannelDialog:**
```
┌─────────────────────────────────────┐
│  Channel erstellen                  │
│─────────────────────────────────────│
│                                     │
│  ┌─────────────────────────┐        │
│  │  Channel-Name           │        │
│  └─────────────────────────┘        │
│                                     │
│  ┌─────────────────────────┐        │
│  │  Beschreibung (optional)│        │
│  └─────────────────────────┘        │
│                                     │
│  ┌──────┐              ┌──────┐    │
│  │Abbrechen│          │Erstellen│   │
│  └──────┘              └──────┘    │
└─────────────────────────────────────┘
```

**Compose-Code-Struktur:**
```kotlin
@Composable
fun ChannelListScreen(
    username: String,
    onChannelSelected: (Channel) -> Unit,
    viewModel: ChannelListViewModel = hiltViewModel()
) {
    val channels by viewModel.channels.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Walkie Talkie") },
                actions = {
                    // User-Avatar mit Name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(username, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Person, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* Dialog öffnen */ }) {
                Icon(Icons.Default.Add, contentDescription = "Channel erstellen")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(channels) { channel ->
                ChannelCard(
                    channel = channel,
                    onClick = { onChannelSelected(channel) }
                )
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Farbindikator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(channel.color)
            )
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium)
                if (channel.description.isNotEmpty()) {
                    Text(
                        channel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Mitgliederanzahl
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("${channel.memberCount}")
            }
        }
    }
}
```

---

#### 3. TalkScreen – Die Push-to-Talk Zentrale (Hauptbildschirm)

```
┌─────────────────────────────────────┐
│  ← Verlassen    📡 Allgemein        │
│─────────────────────────────────────│
│                                     │
│  ─── Mitglieder (3) ───             │
│                                     │
│  🟢 ● Max Müller        ── spricht │
│  ⚪ ○ Anna Schmidt                  │
│  ⚪ ○ Tom Weber                     │
│                                     │
│  ─────────────────────────────      │
│                                     │
│         ╔═══════════════╗           │
│         ║               ║           │
│         ║    🎤 HALTEN  ║           │
│         ║     & SPRECHEN║           │
│         ║               ║           │
│         ╚═══════════════╝           │
│                                     │
│    [🔓 Toggle: Aus]                 │
│                                     │
│  ─────────────────────────────      │
│  Verbunden | Ping: 23ms             │
└─────────────────────────────────────┘
```

**Design-Details – Mitgliederliste:**

Die Mitgliederliste ist ein zentrales Element des TalkScreens. Jeder Benutzer wird als moderne Kachel dargestellt:

```
┌─────────────────────────────────────┐
│  🟢 ● Max Müller                    │  ← Sprecher (grüner Rand + Pulsieren)
│     ── spricht ──                   │
│     🔊 Lautstärke: ████████░░ 80%   │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│  ⚪ ○ Anna Schmidt                   │  ← Zuhörer (neutral)
│     ── hört zu ──                   │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│  🔴 ● Tom Weber                     │  ← Eigener Benutzer (wenn sendend)
│     ── Du sendest ──                │
│     🎤 Mikrofon: AKTIV              │
└─────────────────────────────────────┘
```

**Status-Indikatoren im Detail:**

| Status | Icon | Farbe | Animation | Beschreibung |
|---|---|---|---|---|
| **Spricht** | `●` Gefüllter Kreis | 🟢 Grün (#4CAF50) | Pulsierend (Scale 1.0→1.2) | Benutzer sendet gerade Audio |
| **Hört zu** | `○` Leerer Kreis | ⚪ Grau (#9E9E9E) | Keine | Benutzer ist im Channel, hört aber nur |
| **Sendet selbst** | `●` Gefüllter Kreis | 🔴 Rot (#F44336) | Pulsierend + Rand | Eigener Mikrofon-Status |
| **Stumm** | `○` mit Durchgestrichen | 🔴 Dunkelrot | Keine | Benutzer hat kein Mikrofon |
| **Verbindungsprobleme** | `⚠️` | 🟡 Gelb (#FFC107) | Blinkend | Latenz > 500ms oder Verbindung instabil |

**PTT-Button – Detaillierte Zustände:**

```
═══════════════════════════════════════
         IDLE-Zustand (Standard)
╔═══════════════════════════════════╗
║                                   ║
║          🎤 HALTEN                ║
║        & SPRECHEN                 ║
║                                   ║
║     [Einmal tippen = Dauer-Modus] ║
╚═══════════════════════════════════╝
Farbe: MaterialTheme.colorScheme.surfaceVariant (dunkelgrau)
Icon: Mikrofon (weiß)
Text: "HALTEN & SPRECHEN"

═══════════════════════════════════════
       TRANSMITTING (Gedrückt)
╔═══════════════════════════════════╗
║           █ █ █ █ █              ║
║         █  ███  █                ║
║        █ 🎤 SENDET █             ║
║         █  ███  █                ║
║           █ █ █ █ █              ║
╚═══════════════════════════════════╝
Farbe: Rot (#F44336) mit Pulsieren
Icon: Mikrofon mit Schallwellen (animiert)
Text: "SENDET" (pulsierend)
Hintergrund: Leichte Vibration (haptisches Feedback)

═══════════════════════════════════════
    TOGGLE-MODUS AKTIV (Dauer-Senden)
╔═══════════════════════════════════╗
║           █ █ █ █ █              ║
║         █  ███  █                ║
║        █ 🎤 DAUER █              ║
║         █  ███  █                ║
║           █ █ █ █ █              ║
╚═══════════════════════════════════╝
Farbe: Orange (#FF5722) (Unterscheidung zu gedrückt)
Icon: Mikrofon mit Schloss-Symbol
Text: "DAUER-SENDEN AKTIV"
Zusatz: "Zum Beenden tippen"

═══════════════════════════════════════
       MIKROFON-BLOCKIERT (Fehler)
╔═══════════════════════════════════╗
║                                   ║
║        ⛔ MIKROFON                ║
║        GESPERRT                   ║
║                                   ║
║     [Berechtigung erforderlich]   ║
╚═══════════════════════════════════╝
Farbe: Dunkelrot (#B71C1C)
Icon: Verbotsschild
Text: "MIKROFON GESPERRT"
```

**PTT-Button – Animationsbeschreibung:**

```kotlin
// Pulsierende Animation für den Sendestatus
val infiniteTransition = rememberInfiniteTransition()
val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.1f,
    animationSpec = infiniteRepeatable(
        animation = tween(500, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
    )
)

// Schallwellen-Animation
val waveAlpha by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(300),
        repeatMode = RepeatMode.Reverse
    )
)

// Button-Inhalt je nach Zustand
when (pttState) {
    PttState.IDLE -> {
        // Statischer Button mit Mikrofon-Icon
        Button(
            onClick = { /* Toggle umschalten */ },
            onLongClick = { /* Gedrückt halten start */ },
            modifier = Modifier
                .size(200.dp)
                .graphicsLayer { scaleX = 1f; scaleY = 1f },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(Icons.Default.Mic, contentDescription = "PTT", modifier = Modifier.size(48.dp))
        }
    }
    PttState.TRANSMITTING -> {
        // Pulsierender Button mit Schallwellen
        Box(contentAlignment = Alignment.Center) {
            // Schallwellen-Ringe (animiert)
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(200.dp + (index * 40 * waveAlpha).dp)
                        .alpha(waveAlpha * (1f - index * 0.3f))
                        .background(Color.Red.copy(alpha = 0.2f), CircleShape)
                )
            }
            // Haupt-Button
            Button(
                onClick = { /* Nichts, oder Toggle beenden */ },
                modifier = Modifier
                    .size(200.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                )
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Sendet", modifier = Modifier.size(48.dp))
            }
        }
    }
}
```

**Interaktionslogik (Touch & Click):**

```kotlin
// Kombinierte Erkennung von langem Drücken und einfachem Klick
Modifier.pointerInput(Unit) {
    detectTapGestures(
        onTap = {
            // Einfacher Klick = Toggle-Modus umschalten
            viewModel.toggleTransmitting()
        },
        onLongPress = {
            // Langer Druck = Starte Senden
            viewModel.startTransmitting()
        }
    )
}

// Zusätzlich: Loslassen erkennen
Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            // Wenn Finger losgelassen und NICHT im Toggle-Modus
            if (event.changes.any { it.pressed == false } && !viewModel.isToggleMode) {
                viewModel.stopTransmitting()
            }
        }
    }
}
```

**Statusleiste (unten):**

```
┌─────────────────────────────────────┐
│  ● Verbunden    Ping: 23ms    🔊   │
└─────────────────────────────────────┘
```

- **Verbindungsstatus**: 
  - 🟢 "Verbunden" – grün, normale Verbindung
  - 🟡 "Wiederverbinden..." – gelb, blinkend bei Verbindungsabbruch
  - 🔴 "Getrennt" – rot, wenn keine Verbindung zum Server
- **Ping**: Latenz in Millisekunden (aktualisiert alle 5 Sekunden)
- **Lautstärke**: Kleiner Lautsprecher-Icon, anklickbar für Lautstärkeeinstellungen

**Verlassen-Button:**
- Oben links als Zurück-Pfeil
- Beim Verlassen: Bestätigungsdialog "Channel wirklich verlassen?"
- Nach Verlassen: Zurück zur ChannelListScreen

---

#### 4. Lade- und Fehlerzustände

**Loading-Screen (beim Verbinden):**
```
┌─────────────────────────────────────┐
│                                     │
│         🔄                          │
│     Verbinde zum Server...          │
│                                     │
│    [Kreisel-Animation]              │
│                                     │
└─────────────────────────────────────┘
```

**Error-Screen (bei Verbindungsproblemen):**
```
┌─────────────────────────────────────┐
│                                     │
│         ❌                          │
│   Verbindung fehlgeschlagen         │
│                                     │
│   Bitte überprüfe deine             │
│   Internetverbindung                │
│                                     │
│    ╔═══════════════════════╗        │
│    ║   🔄 Erneut versuchen ║        │
│    ╚═══════════════════════╝        │
│                                     │
└─────────────────────────────────────┘
```

---

#### 5. Übergänge & Navigation

```kotlin
// Verwendet Navigation Compose mit sanften Übergängen
NavHost(navController, startDestination = "login") {
    composable(
        "login",
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) }
    ) { LoginScreen(...) }
    
    composable(
        "channels",
        enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
    ) { ChannelListScreen(...) }
    
    composable(
        "talk/{channelId}",
        enterTransition = { scaleIn() + fadeIn() },
        exitTransition = { scaleOut() + fadeOut() }
    ) { TalkScreen(...) }
}
```

---

#### 6. Zusammenfassung UI/UX

| Screen | Hauptelemente | Interaktion |
|---|---|---|
| **LoginScreen** | App-Icon, Name-Eingabe, Beitreten-Button | Text eingeben, Button tippen |
| **ChannelListScreen** | Channel-Karten, FAB, User-Avatar | Karte tippen = beitreten, FAB = erstellen |
| **TalkScreen** | Mitgliederliste, PTT-Button, Statusleiste | PTT halten/tippen, Mitglieder sehen |
| **CreateChannelDialog** | Name + Beschreibung | Ausfüllen + Erstellen |

**Besondere UX-Features:**
- **Haptisches Feedback** beim Drücken des PTT-Buttons (Vibration)
- **Akustisches Feedback** (kurzer Piepton) beim Start/Ende der Übertragung (optional)
- **Bildschirm-Always-On** während der Übertragung (verhindert Display-Ausschalten)
- **Lautstärke-Anpassung** für eingehendes Audio über einen Slider
- **Benachrichtigungen** wenn jemand zu sprechen beginnt (optional)
- **Dunkles Theme** als Standard (augenschonend, typisch für Kommunikations-Apps)


### Push-to-Talk Logik

```kotlin
// PTT-Button Zustände
enum class PttState {
    IDLE,           // Nichts passiert
    TRANSMITTING,   // Sendet (gedrückt oder Toggle an)
}

// Toggle-Modus
var isToggleMode = false
var isTransmitting = false

fun onPttPressed() {
    if (isToggleMode) {
        // Toggle ausschalten
        isToggleMode = false
        stopTransmitting()
    } else {
        // Normaler Push
        startTransmitting()
    }
}

fun onPttReleased() {
    if (!isToggleMode) {
        stopTransmitting()
    }
    // Im Toggle-Modus: nichts tun
}

fun onPttClicked() {
    // Einmal-Klick = Toggle umschalten
    isToggleMode = !isToggleMode
    if (isToggleMode) {
        startTransmitting()
    } else {
        stopTransmitting()
    }
}
```

### WebRTC-Integration

```kotlin
class WebRTCManager(private val context: Context) {
    private var peerConnectionFactory: PeerConnectionFactory
    private val peers = mutableMapOf<String, PeerConnection>()
    private var localAudioTrack: AudioTrack? = null
    
    // Für jeden Peer im Channel eine PeerConnection
    fun connectToPeer(peerId: String, signalingClient: SignalingClient)
    fun sendAudio(enable: Boolean) // Mikrofon ein/aus
    fun onRemoteAudio(peerId: String, audioData: ByteArray)
    fun disconnectAll()
}
```

**WebRTC-Konfiguration:**
- ICE-Server: Google STUN (`stun:stun.l.google.com:19302`)
- Audio-Codec: Opus (Standard in WebRTC)
- Nur Audio, kein Video

---

## 7. Docker-Setup

### docker-compose.yml (im server/-Verzeichnis)

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: walkie
      POSTGRES_USER: walkie
      POSTGRES_PASSWORD: walkie_secret
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  server:
    build: .
    ports:
      - "3000:3000"
    environment:
      PORT: 3000
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: walkie
      DB_USER: walkie
      DB_PASSWORD: walkie_secret
    depends_on:
      - postgres

volumes:
  pgdata:
```

### Dockerfile (Server)

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
EXPOSE 3000
CMD ["node", "dist/index.js"]
```

---

## 8. Entwicklungs-Roadmap

### Phase 1: Server-Grundgerüst
- [ ] Node.js-Projekt mit TypeScript einrichten
- [ ] PostgreSQL-Schema erstellen (Migration)
- [ ] WebSocket-Server mit `ws`-Bibliothek
- [ ] Login-Logik (Benutzer anlegen/abrufen)
- [ ] Channel CRUD (erstellen, auflisten, beitreten, verlassen)
- [ ] Docker-Compose für Server + PostgreSQL

### Phase 2: Android-Client Grundgerüst
- [ ] Abhängigkeiten hinzufügen (WebSocket, WebRTC, Gson, Navigation)
- [ ] WebSocket-Client-Klasse
- [ ] LoginScreen + LoginViewModel
- [ ] ChannelListScreen + ChannelListViewModel
- [ ] Navigation zwischen Screens

### Phase 3: Audio & WebRTC
- [ ] AudioRecorder (Mikrofon-Zugriff, Opus-Codec)
- [ ] AudioPlayer (Wiedergabe von eingehendem Audio)
- [ ] WebRTCManager (PeerConnection, Audio-Tracks)
- [ ] SignalingClient (SDP/ICE-Austausch über WebSocket)
- [ ] TalkScreen mit PTT-Button

### Phase 4: Integration & Tests
- [ ] Vollständiger Durchlauf: Login → Channel → Talk
- [ ] Mehrere Clients gleichzeitig testen
- [ ] Fehlerbehandlung (Verbindungsabbrüche, Mikrofon-Berechtigungen)
- [ ] Android-Berechtigungen für Mikrofon

### Phase 5: Deployment & Optimierung
- [ ] Server auf VPS deployen (Docker)
- [ ] Latenz-Optimierung
- [ ] UI/UX-Verbesserungen
- [ ] Optional: Nachrichten-Historie, Push-Benachrichtigungen

---

## 9. Wichtige Überlegungen

### Berechtigungen (Android)
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

### Audio-Parameter
- **Sample-Rate:** 48000 Hz (Standard für WebRTC Opus)
- **Channel:** Mono (Sprache)
- **Audio-Source:** VOICE_COMMUNICATION (optimiert für Sprache)
- **Buffer-Größe:** Minimal für niedrige Latenz

### WebRTC vs. Direkter UDP-Stream
| Kriterium | WebRTC | Direkter UDP-Stream |
|---|---|---|
| Latenz | Sehr niedrig (30-100ms) | Niedrig (50-150ms) |
| NAT-Traversal | Ja (ICE/STUN/TURN) | Nein (nur LAN) |
| Codec | Opus (optimiert) | Selbst implementieren |
| Komplexität | Mittel | Hoch |
| **Entscheidung** | ✅ **WebRTC** | |

### Warum WebRTC + PostgreSQL?
- **WebRTC**: Bietet Low-Latency-Audio, NAT-Traversal, Opus-Codec – alles was eine Walkie-Talkie-App braucht
- **PostgreSQL**: Robust, relational, gut für Channel-/Nutzerverwaltung, Docker-kompatibel

---

## 10. Dateien, die erstellt/angepasst werden müssen

### Neu zu erstellende Dateien

**Server:**
- `server/package.json`
- `server/tsconfig.json`
- `server/Dockerfile`
- `server/docker-compose.yml`
- `server/src/index.ts`
- `server/src/config.ts`
- `server/src/database.ts`
- `server/src/websocket/handler.ts`
- `server/src/websocket/types.ts`
- `server/src/services/userService.ts`
- `server/src/services/channelService.ts`
- `server/src/services/signalingService.ts`
- `server/src/models/User.ts`
- `server/src/models/Channel.ts`

**Android Client:**
- `app/src/main/java/com/ronin/walkie/WalkieApplication.kt`
- `app/src/main/java/com/ronin/walkie/audio/AudioRecorder.kt`
- `app/src/main/java/com/ronin/walkie/audio/AudioPlayer.kt`
- `app/src/main/java/com/ronin/walkie/network/WebSocketClient.kt`
- `app/src/main/java/com/ronin/walkie/network/SignalingClient.kt`
- `app/src/main/java/com/ronin/walkie/webrtc/WebRTCManager.kt`
- `app/src/main/java/com/ronin/walkie/model/User.kt`
- `app/src/main/java/com/ronin/walkie/model/Channel.kt`
- `app/src/main/java/com/ronin/walkie/model/Message.kt`
- `app/src/main/java/com/ronin/walkie/viewmodel/LoginViewModel.kt`
- `app/src/main/java/com/ronin/walkie/viewmodel/ChannelListViewModel.kt`
- `app/src/main/java/com/ronin/walkie/viewmodel/ChannelViewModel.kt`
- `app/src/main/java/com/ronin/walkie/ui/login/LoginScreen.kt`
- `app/src/main/java/com/ronin/walkie/ui/channels/ChannelListScreen.kt`
- `app/src/main/java/com/ronin/walkie/ui/channels/CreateChannelDialog.kt`
- `app/src/main/java/com/ronin/walkie/ui/talk/TalkScreen.kt`

### Zu ändernde Dateien
- `app/build.gradle.kts` (neue Dependencies)
- `app/src/main/AndroidManifest.xml` (Berechtigungen)
- `app/src/main/java/com/ronin/walkie/MainActivity.kt` (Navigation, Theme)

---

## 11. Zusammenfassung

Die Walkie-Talkie-App besteht aus zwei Hauptkomponenten:

1. **Node.js-Server** mit WebSocket für Signalisierung und PostgreSQL für Datenhaltung, containerisiert mit Docker
2. **Android-Client** mit Jetpack Compose UI, WebRTC für Audio-Streaming und WebSocket für Signalisierung

Die Kommunikation läuft über:
- **WebSocket**: Login, Channel-Verwaltung, WebRTC-Signalisierung (SDP/ICE)
- **WebRTC**: Direkte Audio-Übertragung zwischen Clients (Peer-to-Peer)

Der Push-to-Talk-Button unterstützt zwei Modi:
- **Gedrückt halten**: Sendet nur solange der Button gedrückt ist
- **Einmal klicken**: Toggelt Dauer-Senden an/aus
