import { WebSocket } from 'ws';
import { ClientMessage } from './types';
import * as userService from '../services/userService';
import * as channelService from '../services/channelService';
import * as signalingService from '../services/signalingService';

// Map: WebSocket -> username
const wsToUser = new Map<WebSocket, string>();

export async function handleMessage(ws: WebSocket, rawData: string): Promise<void> {
  try {
    const message: ClientMessage = JSON.parse(rawData);
    const { type, payload } = message;

    switch (type) {
      case 'login':
        await handleLogin(ws, payload);
        break;
      case 'create_channel':
        await handleCreateChannel(ws, payload);
        break;
      case 'join_channel':
        await handleJoinChannel(ws, payload);
        break;
      case 'leave_channel':
        await handleLeaveChannel(ws, payload);
        break;
      case 'get_channels':
        await handleGetChannels(ws);
        break;
      case 'get_users':
        await handleGetUsers(ws, payload);
        break;
      case 'signal':
        handleSignal(ws, payload);
        break;
      case 'start_talking':
        handleStartTalking(ws, payload);
        break;
      case 'stop_talking':
        handleStopTalking(ws, payload);
        break;
      default:
        sendToClient(ws, {
          type: 'error',
          payload: { message: `Unknown message type: ${type}` }
        });
    }
  } catch (error) {
    console.error('Error handling message:', error);
    sendToClient(ws, {
      type: 'error',
      payload: { message: 'Internal server error' }
    });
  }
}

export function handleDisconnect(ws: WebSocket): void {
  const username = wsToUser.get(ws);
  if (username) {
    // Benutzer aus allen Channels entfernen
    signalingService.unregisterClient(username);
    wsToUser.delete(ws);
    console.log(`❌ Client disconnected: ${username}`);
  }
}

async function handleLogin(ws: WebSocket, payload: { username: string }): Promise<void> {
  const { username } = payload;
  
  if (!username || username.length < 2) {
    sendToClient(ws, {
      type: 'login_error',
      payload: { message: 'Username must be at least 2 characters' }
    });
    return;
  }

  try {
    const user = await userService.loginUser(username);
    signalingService.registerClient(username, ws);
    wsToUser.set(ws, username);

    sendToClient(ws, {
      type: 'login_success',
      payload: { user }
    });

    console.log(`✅ User logged in: ${username}`);
  } catch (error) {
    console.error('Login error:', error);
    sendToClient(ws, {
      type: 'login_error',
      payload: { message: 'Login failed' }
    });
  }
}

async function handleCreateChannel(ws: WebSocket, payload: { name: string; description?: string; color?: string }): Promise<void> {
  const username = wsToUser.get(ws);
  if (!username) {
    sendToClient(ws, { type: 'error', payload: { message: 'Not logged in' } });
    return;
  }

  try {
    const channel = await channelService.createChannel(
      payload.name,
      payload.description || '',
      payload.color || '#4CAF50',
      username
    );

    signalingService.addUserToChannel(username, channel.id);

    sendToClient(ws, {
      type: 'channel_created',
      payload: { channel }
    });

    console.log(`📢 Channel created: ${channel.name} by ${username}`);
  } catch (error: any) {
    sendToClient(ws, {
      type: 'error',
      payload: { message: error.message || 'Failed to create channel' }
    });
  }
}

async function handleJoinChannel(ws: WebSocket, payload: { channelId: number }): Promise<void> {
  const username = wsToUser.get(ws);
  if (!username) {
    sendToClient(ws, { type: 'error', payload: { message: 'Not logged in' } });
    return;
  }

  try {
    const channel = await channelService.getChannelById(payload.channelId);
    if (!channel) {
      sendToClient(ws, { type: 'error', payload: { message: 'Channel not found' } });
      return;
    }

    await channelService.joinChannel(payload.channelId, username);
    signalingService.addUserToChannel(username, payload.channelId);

    // Alle Mitglieder im Channel benachrichtigen
    const members = await channelService.getChannelMembers(payload.channelId);
    const memberUsernames = members.map(m => m.username);

    signalingService.broadcastToChannel(payload.channelId, {
      type: 'user_joined',
      payload: { username, channelId: payload.channelId, users: memberUsernames }
    });

    // Dem beigetretenen User die Userliste senden
    sendToClient(ws, {
      type: 'user_list',
      payload: { channelId: payload.channelId, users: memberUsernames }
    });

    console.log(`👤 ${username} joined channel #${payload.channelId}`);
  } catch (error) {
    console.error('Join channel error:', error);
    sendToClient(ws, { type: 'error', payload: { message: 'Failed to join channel' } });
  }
}

async function handleLeaveChannel(ws: WebSocket, payload: { channelId: number }): Promise<void> {
  const username = wsToUser.get(ws);
  if (!username) {
    sendToClient(ws, { type: 'error', payload: { message: 'Not logged in' } });
    return;
  }

  try {
    await channelService.leaveChannel(payload.channelId, username);
    signalingService.removeUserFromChannel(username, payload.channelId);

    // Andere Mitglieder benachrichtigen
    signalingService.broadcastToChannel(payload.channelId, {
      type: 'user_left',
      payload: { username, channelId: payload.channelId }
    }, username);

    console.log(`👋 ${username} left channel #${payload.channelId}`);
  } catch (error) {
    console.error('Leave channel error:', error);
  }
}

async function handleGetChannels(ws: WebSocket): Promise<void> {
  try {
    const channels = await channelService.getChannels();
    sendToClient(ws, {
      type: 'channel_list',
      payload: { channels }
    });
  } catch (error) {
    console.error('Get channels error:', error);
    sendToClient(ws, { type: 'error', payload: { message: 'Failed to get channels' } });
  }
}

async function handleGetUsers(ws: WebSocket, payload: { channelId: number }): Promise<void> {
  try {
    const members = await channelService.getChannelMembers(payload.channelId);
    const usernames = members.map(m => m.username);
    
    sendToClient(ws, {
      type: 'user_list',
      payload: { channelId: payload.channelId, users: usernames }
    });
  } catch (error) {
    console.error('Get users error:', error);
  }
}

function handleSignal(ws: WebSocket, payload: any): void {
  const username = wsToUser.get(ws);
  if (!username) return;

  signalingService.handleSignal({
    ...payload,
    from: username
  });
}

function handleStartTalking(ws: WebSocket, payload: { channelId: number }): void {
  const username = wsToUser.get(ws);
  if (!username) return;

  signalingService.broadcastToChannel(payload.channelId, {
    type: 'user_talking',
    payload: { username, channelId: payload.channelId, isTalking: true }
  }, username);
}

function handleStopTalking(ws: WebSocket, payload: { channelId: number }): void {
  const username = wsToUser.get(ws);
  if (!username) return;

  signalingService.broadcastToChannel(payload.channelId, {
    type: 'user_stopped_talking',
    payload: { username, channelId: payload.channelId, isTalking: false }
  }, username);
}

function sendToClient(ws: WebSocket, message: any): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
}
