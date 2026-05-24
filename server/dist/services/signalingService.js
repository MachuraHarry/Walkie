"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerClient = registerClient;
exports.unregisterClient = unregisterClient;
exports.addUserToChannel = addUserToChannel;
exports.removeUserFromChannel = removeUserFromChannel;
exports.getUsersInChannel = getUsersInChannel;
exports.sendToUser = sendToUser;
exports.broadcastToChannel = broadcastToChannel;
exports.handleSignal = handleSignal;
exports.getOnlineUsers = getOnlineUsers;
const ws_1 = require("ws");
// Speichert alle verbundenen WebSocket-Clients mit ihren Usernamen
const clients = new Map();
// Speichert welcher User in welchem Channel ist
const userChannels = new Map();
function registerClient(username, ws) {
    clients.set(username, ws);
    if (!userChannels.has(username)) {
        userChannels.set(username, new Set());
    }
    console.log(`🔌 User registered: ${username} (Total: ${clients.size})`);
}
function unregisterClient(username) {
    clients.delete(username);
    userChannels.delete(username);
    console.log(`🔌 User unregistered: ${username} (Total: ${clients.size})`);
}
function addUserToChannel(username, channelId) {
    const channels = userChannels.get(username);
    if (channels) {
        channels.add(channelId);
    }
}
function removeUserFromChannel(username, channelId) {
    const channels = userChannels.get(username);
    if (channels) {
        channels.delete(channelId);
    }
}
function getUsersInChannel(channelId) {
    const users = [];
    for (const [username, channels] of userChannels.entries()) {
        if (channels.has(channelId)) {
            users.push(username);
        }
    }
    return users;
}
function sendToUser(username, message) {
    const ws = clients.get(username);
    if (ws && ws.readyState === ws_1.WebSocket.OPEN) {
        ws.send(JSON.stringify(message));
    }
}
function broadcastToChannel(channelId, message, excludeUsername) {
    const users = getUsersInChannel(channelId);
    for (const username of users) {
        if (username !== excludeUsername) {
            sendToUser(username, message);
        }
    }
}
function handleSignal(signal) {
    const { to } = signal;
    sendToUser(to, {
        type: 'signal',
        payload: signal
    });
}
function getOnlineUsers() {
    return Array.from(clients.keys());
}
//# sourceMappingURL=signalingService.js.map