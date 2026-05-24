import pool from '../database';
import { Channel, ChannelMember } from '../models/Channel';

export async function createChannel(name: string, description: string, color: string, createdBy: string): Promise<Channel> {
  const client = await pool.connect();
  try {
    const result = await client.query<Channel>(
      `INSERT INTO channels (name, description, color, created_by) 
       VALUES ($1, $2, $3, $4) 
       RETURNING *`,
      [name, description, color, createdBy]
    );
    const channel = result.rows[0];
    
    // Ersteller automatisch als Member hinzufügen
    await client.query(
      'INSERT INTO channel_members (channel_id, username) VALUES ($1, $2)',
      [channel.id, createdBy]
    );
    
    return channel;
  } finally {
    client.release();
  }
}

export async function getChannels(): Promise<(Channel & { member_count: number })[]> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT c.*, COUNT(cm.id) as member_count 
       FROM channels c 
       LEFT JOIN channel_members cm ON c.id = cm.channel_id 
       WHERE c.is_active = TRUE 
       GROUP BY c.id 
       ORDER BY c.created_at DESC`
    );
    return result.rows;
  } finally {
    client.release();
  }
}

export async function joinChannel(channelId: number, username: string): Promise<boolean> {
  const client = await pool.connect();
  try {
    await client.query(
      'INSERT INTO channel_members (channel_id, username) VALUES ($1, $2) ON CONFLICT DO NOTHING',
      [channelId, username]
    );
    return true;
  } catch (error) {
    console.error('Error joining channel:', error);
    return false;
  } finally {
    client.release();
  }
}

export async function leaveChannel(channelId: number, username: string): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query(
      'DELETE FROM channel_members WHERE channel_id = $1 AND username = $2',
      [channelId, username]
    );
  } finally {
    client.release();
  }
}

export async function getChannelMembers(channelId: number): Promise<ChannelMember[]> {
  const client = await pool.connect();
  try {
    const result = await client.query<ChannelMember>(
      'SELECT * FROM channel_members WHERE channel_id = $1 ORDER BY joined_at ASC',
      [channelId]
    );
    return result.rows;
  } finally {
    client.release();
  }
}

export async function getChannelById(channelId: number): Promise<Channel | null> {
  const client = await pool.connect();
  try {
    const result = await client.query<Channel>(
      'SELECT * FROM channels WHERE id = $1 AND is_active = TRUE',
      [channelId]
    );
    return result.rows[0] || null;
  } finally {
    client.release();
  }
}
