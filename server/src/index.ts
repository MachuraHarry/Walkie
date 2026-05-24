import express from 'express';
import cors from 'cors';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { config } from './config';

// ============================================================
// DEBUG: Globaler Debug-Modus
// ============================================================
const DEBUG = true;
function debugLog(tag: string, msg: string, data?: any): void {
  const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
  if (data) {
    console.log(`[${timestamp}][${tag}] ${msg}`, JSON.stringify(data));
  } else {
    console.log(`[${timestamp}][${tag}] ${msg}`);
  }
}

// ============================================================
// In-Memory Channel & User Management (keine Datenbank nötig)
// ============================================================

interface Channel {
  id: number;
  name: string;
  description: string;
  color: string;
  createdBy: string;
  createdAt: Date;
  members: Set<string>;
}

interface ClientInfo {
  ws: WebSocket;
  username: string;
  channelId: number | null;
  lastPong: number;
  connectedAt: Date;
}

let nextChannelId = 1;
const channels = new Map<number, Channel>();
const clients = new Map<WebSocket, ClientInfo>();

// ============================================================
// Hilfsfunktionen
// ============================================================

function sendToClient(ws: WebSocket, message: any): void {
  const json = JSON.stringify(message);
  debugLog('SEND', `-> Client: ${json.substring(0, 200)}`);
  if (ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(json);
    } catch (error) {
      debugLog('SEND', `!! Error sending to client: ${error}`);
    }
  } else {
    debugLog('SEND', `!! Client not OPEN (state=${ws.readyState}), cannot send: ${json.substring(0, 100)}`);
  }
}

function broadcastToChannel(channelId: number, message: any, excludeWs?: WebSocket): void {
  const channel = channels.get(channelId);
  if (!channel) {
    debugLog('BROADCAST', `!! Channel #${channelId} not found for broadcast`);
    return;
  }

  debugLog('BROADCAST', `Broadcasting to channel #${channelId} (${channel.members.size} members): ${message.type}`);
  
  let sentCount = 0;
  for (const memberName of channel.members) {
    for (const [ws, info] of clients) {
      if (info.username === memberName && ws !== excludeWs) {
        sendToClient(ws, message);
        sentCount++;
      }
    }
  }
  debugLog('BROADCAST', `Sent to ${sentCount} clients in channel #${channelId}`);
}

function getChannelList(): any[] {
  return Array.from(channels.values()).map(ch => ({
    id: ch.id,
    name: ch.name,
    description: ch.description,
    color: ch.color,
    created_by: ch.createdBy,
    created_at: ch.createdAt.toISOString(),
    is_active: true,
    member_count: ch.members.size,
  }));
}

function getMembersInChannel(channelId: number): string[] {
  const channel = channels.get(channelId);
  return channel ? Array.from(channel.members) : [];
}

// ============================================================
// Express App
// ============================================================

const app = express();
app.use(cors());
app.use(express.json());

app.get('/health', (_req, res) => {
  debugLog('HTTP', 'Health check requested');
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    channels: channels.size,
    clients: clients.size,
    uptime: process.uptime(),
  });
});

// ============================================================
// WebSocket Server
// ============================================================

const server = http.createServer(app);
const wss = new WebSocketServer({ 
  server,
  // WebSocket-Optionen für bessere Stabilität
  maxPayload: 1024 * 1024, // 1MB max payload
  clientTracking: true,
});

wss.on('connection', (ws, req) => {
  const clientIp = req.socket.remoteAddress;
  debugLog('WS', `🔗 New WebSocket connection from ${clientIp}`);
  debugLog('WS', `Total connections: ${wss.clients.size}`);

  // Sende "connected" Nachricht an den Client
  sendToClient(ws, { type: 'connected', payload: { timestamp: Date.now() } });

  ws.on('message', (rawData: Buffer, isBinary: boolean) => {
    if (isBinary) {
      // Binäre Audio-Daten direkt verarbeiten (kein JSON-Parsing nötig)
      debugLog('RECV', `📦 Binary frame received: ${rawData.length} bytes`);
      handleBinaryAudioData(ws, rawData);
      return;
    }

    try {
      const rawStr = rawData.toString();
      debugLog('RECV', `📩 Raw message (${rawStr.length} chars): ${rawStr.substring(0, 300)}`);
      const message = JSON.parse(rawStr);
      handleMessage(ws, message);
    } catch (error) {
      debugLog('RECV', `❌ Error parsing message: ${error}`);
      sendToClient(ws, { type: 'error', payload: { message: 'Invalid message format' } });
    }
  });






  ws.on('close', (code, reason) => {
    debugLog('WS', `🔌 Connection closed: code=${code}, reason=${reason?.toString() || 'none'}`);
    handleDisconnect(ws);
  });

  ws.on('error', (error) => {
    debugLog('WS', `💥 WebSocket error: ${error.message}`);
    handleDisconnect(ws);
  });

  // Ping/Pong für Verbindungsüberwachung
  ws.on('pong', () => {
    const info = clients.get(ws);
    if (info) {
      info.lastPong = Date.now();
    }
    debugLog('WS', '🏓 Pong received from client');
  });
});

// Periodischer Ping an alle Clients (alle 10s statt 30s für schnellere Erkennung)
setInterval(() => {
  const now = Date.now();
  debugLog('WS', `🏓 Pinging ${wss.clients.size} clients...`);
  
  wss.clients.forEach((ws) => {
    if (ws.readyState === WebSocket.OPEN) {
      try {
        ws.ping();
        
        // Prüfe ob Client noch lebt (kein Pong seit 30s)
        const info = clients.get(ws);
        if (info && (now - info.lastPong) > 30000) {
          debugLog('WS', `💀 Client "${info.username}" timed out (no pong for ${(now - info.lastPong) / 1000}s), terminating`);
          ws.terminate();
        }
      } catch (error) {
        debugLog('WS', `!! Error pinging client: ${error}`);
      }
    }
  });
}, 10000);

// ============================================================
// Message Handler
// ============================================================

function handleMessage(ws: WebSocket, message: { type: string; payload?: any }): void {
  const { type, payload } = message;
  debugLog('HANDLE', `Processing message type="${type}"`, payload ? { ...payload, data: payload.data ? `[${payload.data.toString().substring(0, 50)}...]` : undefined } : undefined);

  switch (type) {
    case 'login':
      handleLogin(ws, payload);
      break;
    case 'get_channels':
      handleGetChannels(ws);
      break;
    case 'create_channel':
      handleCreateChannel(ws, payload);
      break;
    case 'join_channel':
      handleJoinChannel(ws, payload);
      break;
    case 'leave_channel':
      handleLeaveChannel(ws, payload);
      break;
    case 'get_users':
      handleGetUsers(ws, payload);
      break;
    case 'start_talking':
      handleStartTalking(ws, payload);
      break;
    case 'stop_talking':
      handleStopTalking(ws, payload);
      break;
    case 'audio_data':
      handleAudioData(ws, payload);
      break;
    case 'ping':
      // Client-seitiger Ping - sofort mit Pong antworten
      sendToClient(ws, { type: 'pong', payload: { timestamp: Date.now() } });
      break;
    default:
      debugLog('HANDLE', `⚠️ Unknown message type: "${type}"`);
      sendToClient(ws, { type: 'error', payload: { message: `Unknown type: ${type}` } });
  }
}

function handleDisconnect(ws: WebSocket): void {
  const info = clients.get(ws);
  if (info) {
    debugLog('DISCONNECT', `Handling disconnect for user "${info.username}"`);
    
    // Aus Channel entfernen
    if (info.channelId !== null) {
      const channel = channels.get(info.channelId);
      if (channel) {
        debugLog('DISCONNECT', `Removing "${info.username}" from channel #${info.channelId} (${channel.name})`);
        channel.members.delete(info.username);
        broadcastToChannel(info.channelId, {
          type: 'user_left',
          payload: { username: info.username, channelId: info.channelId, users: getMembersInChannel(info.channelId) }
        }, ws);
        // Channel nicht löschen, damit Benutzer später wieder beitreten können
        debugLog('DISCONNECT', `Channel #${info.channelId} now has ${channel.members.size} members`);
      } else {
        debugLog('DISCONNECT', `Channel #${info.channelId} not found for user "${info.username}"`);
      }
    }
    clients.delete(ws);
    debugLog('DISCONNECT', `❌ Client disconnected: ${info.username} (Total: ${clients.size})`);
  } else {
    debugLog('DISCONNECT', 'Unknown client disconnected (was not logged in)');
  }
}

function handleLogin(ws: WebSocket, payload: { username: string }): void {
  const { username } = payload;
  debugLog('LOGIN', `Login attempt with username="${username}"`);

  if (!username || username.length < 2) {
    debugLog('LOGIN', `❌ Login failed: username too short`);
    sendToClient(ws, { type: 'login_error', payload: { message: 'Username must be at least 2 characters' } });
    return;
  }

  // Prüfen ob Username bereits online ist
  for (const [, info] of clients) {
    if (info.username === username) {
      debugLog('LOGIN', `❌ Login failed: username "${username}" already taken`);
      sendToClient(ws, { type: 'login_error', payload: { message: 'Username already taken' } });
      return;
    }
  }

  clients.set(ws, { ws, username, channelId: null, lastPong: Date.now(), connectedAt: new Date() });
  debugLog('LOGIN', `✅ User logged in: ${username} (Total: ${clients.size})`);
  
  const response = { type: 'login_success', payload: { user: { id: 0, username, joined_at: new Date().toISOString(), last_active: new Date().toISOString() } } };
  debugLog('LOGIN', `Sending login_success to ${username}`);
  sendToClient(ws, response);
}

function handleGetChannels(ws: WebSocket): void {
  const info = clients.get(ws);
  debugLog('CHANNELS', `get_channels requested by "${info?.username || 'unknown'}"`);
  
  const channelList = getChannelList();
  debugLog('CHANNELS', `Returning ${channelList.length} channels`);
  sendToClient(ws, { type: 'channel_list', payload: { channels: channelList } });
}

function handleCreateChannel(ws: WebSocket, payload: { name: string; description?: string; color?: string }): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('CREATE', '❌ Not logged in');
    sendToClient(ws, { type: 'error', payload: { message: 'Not logged in' } });
    return;
  }

  const { name, description = '', color = '#4CAF50' } = payload;
  debugLog('CREATE', `User "${info.username}" creating channel: name="${name}"`);

  if (!name || name.length < 2) {
    debugLog('CREATE', '❌ Channel name too short');
    sendToClient(ws, { type: 'error', payload: { message: 'Channel name must be at least 2 characters' } });
    return;
  }

  const id = nextChannelId++;
  const channel: Channel = {
    id,
    name,
    description,
    color,
    createdBy: info.username,
    createdAt: new Date(),
    members: new Set([info.username]),
  };

  channels.set(id, channel);
  info.channelId = id;

  debugLog('CREATE', `✅ Channel #${id} "${name}" created by "${info.username}"`);
  debugLog('CREATE', `Total channels: ${channels.size}`);

  // Allen verbundenen Clients die aktualisierte Channel-Liste senden
  const channelList = getChannelList();
  debugLog('CREATE', `Broadcasting updated channel_list to all ${clients.size} clients (${channelList.length} channels)`);
  for (const [clientWs] of clients) {
    sendToClient(clientWs, {
      type: 'channel_list',
      payload: { channels: channelList }
    });
  }
}

function handleJoinChannel(ws: WebSocket, payload: { channelId: number }): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('JOIN', '❌ Not logged in');
    sendToClient(ws, { type: 'error', payload: { message: 'Not logged in' } });
    return;
  }

  debugLog('JOIN', `User "${info.username}" joining channel #${payload.channelId}`);

  const channel = channels.get(payload.channelId);
  if (!channel) {
    debugLog('JOIN', `❌ Channel #${payload.channelId} not found`);
    sendToClient(ws, { type: 'error', payload: { message: 'Channel not found' } });
    return;
  }

  channel.members.add(info.username);
  info.channelId = payload.channelId;

  const members = getMembersInChannel(payload.channelId);
  debugLog('JOIN', `✅ "${info.username}" joined channel #${payload.channelId} (${channel.name}), members: ${members}`);

  // Alle im Channel benachrichtigen
  broadcastToChannel(payload.channelId, {
    type: 'user_joined',
    payload: { username: info.username, channelId: payload.channelId, users: members }
  });

  // Dem beigetretenen User die Userliste senden
  debugLog('JOIN', `Sending user_list to "${info.username}": ${members}`);
  sendToClient(ws, {
    type: 'user_list',
    payload: { channelId: payload.channelId, users: members }
  });
}

function handleLeaveChannel(ws: WebSocket, payload: { channelId: number }): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('LEAVE', 'Not logged in, ignoring');
    return;
  }

  debugLog('LEAVE', `User "${info.username}" leaving channel #${payload.channelId}`);

  const channel = channels.get(payload.channelId);
  if (channel) {
    channel.members.delete(info.username);
    broadcastToChannel(payload.channelId, {
      type: 'user_left',
      payload: { username: info.username, channelId: payload.channelId, users: getMembersInChannel(payload.channelId) }
    });

    // Channel nicht löschen, damit Benutzer später wieder beitreten können
    debugLog('LEAVE', `Channel #${payload.channelId} now has ${channel.members.size} members`);
  }

  info.channelId = null;
  debugLog('LEAVE', `👋 "${info.username}" left channel #${payload.channelId}`);
}

function handleGetUsers(ws: WebSocket, payload: { channelId: number }): void {
  const info = clients.get(ws);
  debugLog('USERS', `get_users for channel #${payload.channelId} requested by "${info?.username || 'unknown'}"`);
  
  const members = getMembersInChannel(payload.channelId);
  debugLog('USERS', `Members in channel #${payload.channelId}: ${members}`);
  
  sendToClient(ws, {
    type: 'user_list',
    payload: { channelId: payload.channelId, users: members }
  });
}

function handleStartTalking(ws: WebSocket, payload: { channelId: number }): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('TALK', 'Not logged in, ignoring start_talking');
    return;
  }

  debugLog('TALK', `🔴 "${info.username}" started talking in channel #${payload.channelId}`);
  
  broadcastToChannel(payload.channelId, {
    type: 'user_talking',
    payload: { username: info.username, channelId: payload.channelId, isTalking: true }
  }, ws);
}

function handleStopTalking(ws: WebSocket, payload: { channelId: number }): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('TALK', 'Not logged in, ignoring stop_talking');
    return;
  }

  debugLog('TALK', `🟢 "${info.username}" stopped talking in channel #${payload.channelId}`);
  
  broadcastToChannel(payload.channelId, {
    type: 'user_stopped_talking',
    payload: { username: info.username, channelId: payload.channelId, isTalking: false }
  }, ws);
}

// ============================================================
// Audio Relay - Der Kern der Walkie-Talkie Funktionalität
// ============================================================

/**
 * Binäres Audio-Frame-Format:
 * 
 * [1 Byte Channel-ID Länge][Channel-ID UTF-8][1 Byte Username Länge][Username UTF-8][PCM-Daten]
 * 
 * Vereinfachte Variante: Wir senden zuerst einen JSON-Header (Text-Frame),
 * dann die Binär-Daten (Binary-Frame). Der Client erwartet nach einem
 * "audio_meta"-Text-Frame den nächsten Binary-Frame als Audio-Daten.
 * 
 * Noch einfacher (und besser): Wir packen alles in ein Binary-Frame:
 * 
 * Byte 0-3:   Channel-ID (Int32, Little-Endian)
 * Byte 4-7:   Username-Länge (Int32, Little-Endian)
 * Byte 8..n:  Username (UTF-8)
 * Byte n+1..: PCM-Audio-Daten (roh)
 */

const INT32_SIZE = 4; // 4 Bytes pro Int32

function handleAudioData(ws: WebSocket, payload: { channelId: number; data: string }): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('AUDIO', 'Not logged in, ignoring audio data');
    return;
  }

  if (info.channelId !== payload.channelId) {
    debugLog('AUDIO', `User "${info.username}" is not in channel #${payload.channelId} (in #${info.channelId}), ignoring audio`);
    return;
  }

  const dataSize = payload.data ? payload.data.length : 0;
  debugLog('AUDIO', `🎤 Audio data from "${info.username}" in channel #${payload.channelId}: ${dataSize} bytes (base64)`);

  // Audio-Daten direkt an alle anderen Mitglieder im Channel weiterleiten
  broadcastToChannel(payload.channelId, {
    type: 'audio_data',
    payload: {
      username: info.username,
      channelId: payload.channelId,
      data: payload.data,
    }
  }, ws);
}

/**
 * Verarbeitet eingehende binäre Audio-Daten.
 * Format: [ChannelId:4Bytes][UsernameLen:4Bytes][Username:UTF8][PCM-Daten]
 */
function handleBinaryAudioData(ws: WebSocket, binaryData: Buffer): void {
  const info = clients.get(ws);
  if (!info) {
    debugLog('AUDIO_BIN', 'Not logged in, ignoring binary audio');
    return;
  }

  if (binaryData.length < INT32_SIZE * 2) {
    debugLog('AUDIO_BIN', `Binary frame too short: ${binaryData.length} bytes`);
    return;
  }

  let offset = 0;
  
  // Channel-ID lesen (Int32, Little-Endian)
  const channelId = binaryData.readInt32LE(offset);
  offset += INT32_SIZE;
  
  // Username-Länge lesen (Int32, Little-Endian)
  const usernameLen = binaryData.readInt32LE(offset);
  offset += INT32_SIZE;
  
  if (offset + usernameLen > binaryData.length) {
    debugLog('AUDIO_BIN', `Invalid username length: ${usernameLen}, buffer=${binaryData.length}`);
    return;
  }
  
  // Username lesen (UTF-8)
  const username = binaryData.toString('utf8', offset, offset + usernameLen);
  offset += usernameLen;
  
  // Rest sind PCM-Daten
  const pcmData = binaryData.subarray(offset);
  
  debugLog('AUDIO_BIN', `🎤 Binary audio from "${username}" in channel #${channelId}: ${pcmData.length} PCM bytes`);

  if (info.channelId !== channelId) {
    debugLog('AUDIO_BIN', `User "${info.username}" is not in channel #${channelId} (in #${info.channelId}), ignoring`);
    return;
  }

  // Binär an alle anderen im Channel weiterleiten
  broadcastBinaryAudio(channelId, binaryData, ws);
}

/**
 * Broadcastet binäre Audio-Daten an alle Mitglieder eines Channels (außer dem Sender).
 */
function broadcastBinaryAudio(channelId: number, binaryData: Buffer, excludeWs?: WebSocket): void {
  const channel = channels.get(channelId);
  if (!channel) {
    debugLog('AUDIO_BIN', `Channel #${channelId} not found for binary broadcast`);
    return;
  }

  let sentCount = 0;
  for (const memberName of channel.members) {
    for (const [ws, info] of clients) {
      if (info.username === memberName && ws !== excludeWs) {
        if (ws.readyState === WebSocket.OPEN) {
          try {
            ws.send(binaryData);
            sentCount++;
          } catch (error) {
            debugLog('AUDIO_BIN', `Error sending binary to ${info.username}: ${error}`);
          }
        }
      }
    }
  }
  debugLog('AUDIO_BIN', `Sent binary audio to ${sentCount} clients in channel #${channelId}`);
}

// ============================================================
// Server Start
// ============================================================

server.listen(config.port, '0.0.0.0', () => {
  console.log(`
╔══════════════════════════════════════════╗
║        Walkie Talkie Server v2.1         ║
║──────────────────────────────────────────║
║  WebSocket: ws://0.0.0.0:${config.port}         ║
║  Health:    http://0.0.0.0:${config.port}/health ║
║  Mode:      Audio Relay (kein WebRTC)    ║
║  Ping:      10s interval, 30s timeout    ║
║  DEBUG:     ENABLED                      ║
╚══════════════════════════════════════════╝
  `);
});
