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
    ws.send(json);
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
  });
});

// ============================================================
// WebSocket Server
// ============================================================

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  const clientIp = req.socket.remoteAddress;
  debugLog('WS', `🔗 New WebSocket connection from ${clientIp}`);
  debugLog('WS', `Total connections: ${wss.clients.size}`);

  ws.on('message', (rawData) => {
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
    debugLog('WS', '🏓 Pong received from client');
  });
});

// Periodischer Ping an alle Clients
setInterval(() => {
  debugLog('WS', `🏓 Pinging ${wss.clients.size} clients...`);
  wss.clients.forEach((ws) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.ping();
    }
  });
}, 30000);

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
        // Channel löschen wenn leer
        if (channel.members.size === 0) {
          channels.delete(info.channelId);
          debugLog('DISCONNECT', `🗑️ Channel #${info.channelId} deleted (empty)`);
        }
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

  clients.set(ws, { ws, username, channelId: null });
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

  // Allen verbundenen Clients Bescheid geben, damit alle die Channel-Liste aktualisieren
  const channelPayload = { channel: { id, name, description, color, created_by: info.username, created_at: channel.createdAt.toISOString(), is_active: true, member_count: 1 } };
  debugLog('CREATE', `Broadcasting channel_created to all ${clients.size} clients`);
  for (const [clientWs] of clients) {
    sendToClient(clientWs, {
      type: 'channel_created',
      payload: channelPayload
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

    if (channel.members.size === 0) {
      channels.delete(payload.channelId);
      debugLog('LEAVE', `🗑️ Channel #${payload.channelId} deleted (empty)`);
    }
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

// ============================================================
// Server Start
// ============================================================

server.listen(config.port, '0.0.0.0', () => {
  console.log(`
╔══════════════════════════════════════════╗
║        Walkie Talkie Server v2           ║
║──────────────────────────────────────────║
║  WebSocket: ws://0.0.0.0:${config.port}         ║
║  Health:    http://0.0.0.0:${config.port}/health ║
║  Mode:      Audio Relay (kein WebRTC)    ║
║  DEBUG:     ENABLED                      ║
╚══════════════════════════════════════════╝
  `);
});
