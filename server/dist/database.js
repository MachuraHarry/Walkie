"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.initializeDatabase = initializeDatabase;
const pg_1 = require("pg");
const config_1 = require("./config");
const pool = new pg_1.Pool({
    host: config_1.config.db.host,
    port: config_1.config.db.port,
    database: config_1.config.db.database,
    user: config_1.config.db.user,
    password: config_1.config.db.password,
});
async function initializeDatabase() {
    const client = await pool.connect();
    try {
        await client.query(`
      CREATE TABLE IF NOT EXISTS users (
        id SERIAL PRIMARY KEY,
        username VARCHAR(50) UNIQUE NOT NULL,
        joined_at TIMESTAMP DEFAULT NOW(),
        last_active TIMESTAMP DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS channels (
        id SERIAL PRIMARY KEY,
        name VARCHAR(100) UNIQUE NOT NULL,
        description VARCHAR(255) DEFAULT '',
        color VARCHAR(7) DEFAULT '#4CAF50',
        created_by VARCHAR(50) REFERENCES users(username),
        created_at TIMESTAMP DEFAULT NOW(),
        is_active BOOLEAN DEFAULT TRUE
      );

      CREATE TABLE IF NOT EXISTS channel_members (
        id SERIAL PRIMARY KEY,
        channel_id INTEGER REFERENCES channels(id) ON DELETE CASCADE,
        username VARCHAR(50) REFERENCES users(username) ON DELETE CASCADE,
        joined_at TIMESTAMP DEFAULT NOW(),
        UNIQUE(channel_id, username)
      );
    `);
        console.log('✅ Database tables initialized');
    }
    finally {
        client.release();
    }
}
exports.default = pool;
//# sourceMappingURL=database.js.map