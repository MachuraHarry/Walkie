import { WebSocket } from 'ws';
import { SignalMessage } from '../websocket/types';

// Speichert alle verbundenen WebSocket-Clients mit ihren Usernamen
const clients = new Map<string, WebSocket>();

// Speichert welcher User in welchem Channel ist
const userChannels = new Map<string, Set<number>>();

export function registerClient(username: string, ws: WebSocket): void {
  clients.set(username, ws);
  if (!userChannels.has(username)) {
    userChannels.set(username, new Set());
  }
  console.log(`🔌 User registered: ${username} (Total: ${clients.size})`);
}

export function unregisterClient(username: string): void {
  clients.delete(username);
  userChannels.delete(username);
  console.log(`🔌 User unregistered: ${username} (Total: ${clients.size})`);
}

export function addUserToChannel(username: string, channelId: number): void {
  const channels = userChannels.get(username);
  if (channels) {
    channels.add(channelId);
  }
}

export function removeUserFromChannel(username: string, channelId: number): void {
  const channels = userChannels.get(username);
  if (channels) {
    channels.delete(channelId);
  }
}

export function getUsersInChannel(channelId: number): string[] {
  const users: string[] = [];
  for (const [username, channels] of userChannels.entries()) {
    if (channels.has(channelId)) {
      users.push(username);
    }
  }
  return users;
}

export function sendToUser(username: string, message: any): void {
  const ws = clients.get(username);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
}

export function broadcastToChannel(channelId: number, message: any, excludeUsername?: string): void {
  const users = getUsersInChannel(channelId);
  for (const username of users) {
    if (username !== excludeUsername) {
      sendToUser(username, message);
    }
  }
}

export function handleSignal(signal: SignalMessage): void {
  const { to } = signal;
  sendToUser(to, {
    type: 'signal',
    payload: signal
  });
}

export function getOnlineUsers(): string[] {
  return Array.from(clients.keys());
}
