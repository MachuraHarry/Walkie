"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const cors_1 = __importDefault(require("cors"));
const http_1 = __importDefault(require("http"));
const ws_1 = require("ws");
const config_1 = require("./config");
const database_1 = require("./database");
const handler_1 = require("./websocket/handler");
async function main() {
    // Datenbank initialisieren
    await (0, database_1.initializeDatabase)();
    // Express-App für Health-Check
    const app = (0, express_1.default)();
    app.use((0, cors_1.default)());
    app.use(express_1.default.json());
    app.get('/health', (_req, res) => {
        res.json({ status: 'ok', timestamp: new Date().toISOString() });
    });
    // HTTP-Server erstellen
    const server = http_1.default.createServer(app);
    // WebSocket-Server
    const wss = new ws_1.WebSocketServer({ server });
    wss.on('connection', (ws) => {
        console.log('🔗 New WebSocket connection');
        ws.on('message', (data) => {
            (0, handler_1.handleMessage)(ws, data.toString());
        });
        ws.on('close', () => {
            (0, handler_1.handleDisconnect)(ws);
        });
        ws.on('error', (error) => {
            console.error('WebSocket error:', error);
            (0, handler_1.handleDisconnect)(ws);
        });
    });
    server.listen(config_1.config.port, () => {
        console.log(`
╔══════════════════════════════════════════╗
║        Walkie Talkie Server              ║
║──────────────────────────────────────────║
║  WebSocket: ws://localhost:${config_1.config.port}        ║
║  Health:    http://localhost:${config_1.config.port}/health ║
╚══════════════════════════════════════════╝
    `);
    });
}
main().catch((error) => {
    console.error('Failed to start server:', error);
    process.exit(1);
});
//# sourceMappingURL=index.js.map