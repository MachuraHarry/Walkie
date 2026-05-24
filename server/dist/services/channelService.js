"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.createChannel = createChannel;
exports.getChannels = getChannels;
exports.joinChannel = joinChannel;
exports.leaveChannel = leaveChannel;
exports.getChannelMembers = getChannelMembers;
exports.getChannelById = getChannelById;
const database_1 = __importDefault(require("../database"));
async function createChannel(name, description, color, createdBy) {
    const client = await database_1.default.connect();
    try {
        const result = await client.query(`INSERT INTO channels (name, description, color, created_by) 
       VALUES ($1, $2, $3, $4) 
       RETURNING *`, [name, description, color, createdBy]);
        const channel = result.rows[0];
        // Ersteller automatisch als Member hinzufügen
        await client.query('INSERT INTO channel_members (channel_id, username) VALUES ($1, $2)', [channel.id, createdBy]);
        return channel;
    }
    finally {
        client.release();
    }
}
async function getChannels() {
    const client = await database_1.default.connect();
    try {
        const result = await client.query(`SELECT c.*, COUNT(cm.id) as member_count 
       FROM channels c 
       LEFT JOIN channel_members cm ON c.id = cm.channel_id 
       WHERE c.is_active = TRUE 
       GROUP BY c.id 
       ORDER BY c.created_at DESC`);
        return result.rows;
    }
    finally {
        client.release();
    }
}
async function joinChannel(channelId, username) {
    const client = await database_1.default.connect();
    try {
        await client.query('INSERT INTO channel_members (channel_id, username) VALUES ($1, $2) ON CONFLICT DO NOTHING', [channelId, username]);
        return true;
    }
    catch (error) {
        console.error('Error joining channel:', error);
        return false;
    }
    finally {
        client.release();
    }
}
async function leaveChannel(channelId, username) {
    const client = await database_1.default.connect();
    try {
        await client.query('DELETE FROM channel_members WHERE channel_id = $1 AND username = $2', [channelId, username]);
    }
    finally {
        client.release();
    }
}
async function getChannelMembers(channelId) {
    const client = await database_1.default.connect();
    try {
        const result = await client.query('SELECT * FROM channel_members WHERE channel_id = $1 ORDER BY joined_at ASC', [channelId]);
        return result.rows;
    }
    finally {
        client.release();
    }
}
async function getChannelById(channelId) {
    const client = await database_1.default.connect();
    try {
        const result = await client.query('SELECT * FROM channels WHERE id = $1 AND is_active = TRUE', [channelId]);
        return result.rows[0] || null;
    }
    finally {
        client.release();
    }
}
//# sourceMappingURL=channelService.js.map