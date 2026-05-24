"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleMessage = handleMessage;
exports.handleDisconnect = handleDisconnect;
const ws_1 = require("ws");
const userService = __importStar(require("../services/userService"));
const channelService = __importStar(require("../services/channelService"));
const signalingService = __importStar(require("../services/signalingService"));
// Map: WebSocket -> username
const wsToUser = new Map();
async function handleMessage(ws, rawData) {
    try {
        const message = JSON.parse(rawData);
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
    }
    catch (error) {
        console.error('Error handling message:', error);
        sendToClient(ws, {
            type: 'error',
            payload: { message: 'Internal server error' }
        });
    }
}
function handleDisconnect(ws) {
    const username = wsToUser.get(ws);
    if (username) {
        // Benutzer aus allen Channels entfernen
        signalingService.unregisterClient(username);
        wsToUser.delete(ws);
        console.log(`❌ Client disconnected: ${username}`);
    }
}
async function handleLogin(ws, payload) {
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
    }
    catch (error) {
        console.error('Login error:', error);
        sendToClient(ws, {
            type: 'login_error',
            payload: { message: 'Login failed' }
        });
    }
}
async function handleCreateChannel(ws, payload) {
    const username = wsToUser.get(ws);
    if (!username) {
        sendToClient(ws, { type: 'error', payload: { message: 'Not logged in' } });
        return;
    }
    try {
        const channel = await channelService.createChannel(payload.name, payload.description || '', payload.color || '#4CAF50', username);
        signalingService.addUserToChannel(username, channel.id);
        sendToClient(ws, {
            type: 'channel_created',
            payload: { channel }
        });
        console.log(`📢 Channel created: ${channel.name} by ${username}`);
    }
    catch (error) {
        sendToClient(ws, {
            type: 'error',
            payload: { message: error.message || 'Failed to create channel' }
        });
    }
}
async function handleJoinChannel(ws, payload) {
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
    }
    catch (error) {
        console.error('Join channel error:', error);
        sendToClient(ws, { type: 'error', payload: { message: 'Failed to join channel' } });
    }
}
async function handleLeaveChannel(ws, payload) {
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
    }
    catch (error) {
        console.error('Leave channel error:', error);
    }
}
async function handleGetChannels(ws) {
    try {
        const channels = await channelService.getChannels();
        sendToClient(ws, {
            type: 'channel_list',
            payload: { channels }
        });
    }
    catch (error) {
        console.error('Get channels error:', error);
        sendToClient(ws, { type: 'error', payload: { message: 'Failed to get channels' } });
    }
}
async function handleGetUsers(ws, payload) {
    try {
        const members = await channelService.getChannelMembers(payload.channelId);
        const usernames = members.map(m => m.username);
        sendToClient(ws, {
            type: 'user_list',
            payload: { channelId: payload.channelId, users: usernames }
        });
    }
    catch (error) {
        console.error('Get users error:', error);
    }
}
function handleSignal(ws, payload) {
    const username = wsToUser.get(ws);
    if (!username)
        return;
    signalingService.handleSignal({
        ...payload,
        from: username
    });
}
function handleStartTalking(ws, payload) {
    const username = wsToUser.get(ws);
    if (!username)
        return;
    signalingService.broadcastToChannel(payload.channelId, {
        type: 'user_talking',
        payload: { username, channelId: payload.channelId, isTalking: true }
    }, username);
}
function handleStopTalking(ws, payload) {
    const username = wsToUser.get(ws);
    if (!username)
        return;
    signalingService.broadcastToChannel(payload.channelId, {
        type: 'user_stopped_talking',
        payload: { username, channelId: payload.channelId, isTalking: false }
    }, username);
}
function sendToClient(ws, message) {
    if (ws.readyState === ws_1.WebSocket.OPEN) {
        ws.send(JSON.stringify(message));
    }
}
//# sourceMappingURL=handler.js.map