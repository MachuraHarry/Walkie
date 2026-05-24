# Backend Overhaul - Task Progress

## Strategy: Direct WebSocket Audio Streaming (statt WebRTC)

Statt WebRTC Peer-to-Peer verwenden wir einen **Client-Server Audio Relay** Ansatz:
- Client nimmt PCM-Audio auf und sendet es direkt per WebSocket an den Server
- Server broadcasted die Audio-Daten an alle anderen Clients im selben Channel
- Client spielt die empfangenen Audio-Daten ab

### Vorteile:
- Kein STUN/TURN nötig
- Keine ICE-Negotiation
- Funktioniert zuverlässig im Emulator und über verschiedene Netzwerke
- Deutlich einfachere Codebasis
- Weniger Abhängigkeiten (kein WebRTC-SDK nötig)

## Tasks

### Server (TypeScript)
- [ ] Server komplett neu schreiben: Audio-Relay statt Signaling
- [ ] WebSocket-Nachrichten für Audio-Streaming
- [ ] Channel-Management (vereinfacht, ohne DB)
- [ ] Docker-Konfiguration aktualisieren

### App Backend (Kotlin)
- [ ] WebSocketClient optimieren für Audio-Streaming
- [ ] AudioRecorder an WebSocket anbinden
- [ ] AudioPlayer an WebSocket anbinden
- [ ] WebRTCManager und SignalingClient entfernen/ersetzen
- [ ] ViewModels an neue Architektur anpassen
- [ ] WalkieApplication vereinfachen
