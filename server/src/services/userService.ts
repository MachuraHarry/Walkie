import pool from '../database';
import { User } from '../models/User';

export async function loginUser(username: string): Promise<User> {
  const client = await pool.connect();
  try {
    // Versuche Benutzer zu finden, sonst erstellen
    const result = await client.query<User>(
      `INSERT INTO users (username, last_active) 
       VALUES ($1, NOW()) 
       ON CONFLICT (username) 
       DO UPDATE SET last_active = NOW() 
       RETURNING *`,
      [username]
    );
    return result.rows[0];
  } finally {
    client.release();
  }
}

export async function getUser(username: string): Promise<User | null> {
  const client = await pool.connect();
  try {
    const result = await client.query<User>(
      'SELECT * FROM users WHERE username = $1',
      [username]
    );
    return result.rows[0] || null;
  } finally {
    client.release();
  }
}

export async function updateLastActive(username: string): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query(
      'UPDATE users SET last_active = NOW() WHERE username = $1',
      [username]
    );
  } finally {
    client.release();
  }
}
