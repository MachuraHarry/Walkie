"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.loginUser = loginUser;
exports.getUser = getUser;
exports.updateLastActive = updateLastActive;
const database_1 = __importDefault(require("../database"));
async function loginUser(username) {
    const client = await database_1.default.connect();
    try {
        // Versuche Benutzer zu finden, sonst erstellen
        const result = await client.query(`INSERT INTO users (username, last_active) 
       VALUES ($1, NOW()) 
       ON CONFLICT (username) 
       DO UPDATE SET last_active = NOW() 
       RETURNING *`, [username]);
        return result.rows[0];
    }
    finally {
        client.release();
    }
}
async function getUser(username) {
    const client = await database_1.default.connect();
    try {
        const result = await client.query('SELECT * FROM users WHERE username = $1', [username]);
        return result.rows[0] || null;
    }
    finally {
        client.release();
    }
}
async function updateLastActive(username) {
    const client = await database_1.default.connect();
    try {
        await client.query('UPDATE users SET last_active = NOW() WHERE username = $1', [username]);
    }
    finally {
        client.release();
    }
}
//# sourceMappingURL=userService.js.map