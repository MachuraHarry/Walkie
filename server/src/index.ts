import express from 'express';
import cors from 'cors';
import http from 'http';
import { WebSocketServer } from 'ws';
import { config } from './config';
import { initializeDatabase } from './database';
import { handleMessage, handleDisconnect } from './websocket/handler';

async function main() {
  // Datenbank initialisieren
  await initializeDatabase();

  // Express-App für Health-Check
  const app = express();
  app.use(cors());
  app.use(express.json());

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
  });

  // HTTP-Server erstellen
  const server = http.createServer(app);

  // WebSocket-Server
  const wss = new WebSocketServer({ server });

  wss.on('connection', (ws) => {
    console.log('🔗 New WebSocket connection');

    ws.on('message', (data) => {
      handleMessage(ws, data.toString());
    });

    ws.on('close', () => {
      handleDisconnect(ws);
    });

    ws.on('error', (error) => {
      console.error('WebSocket error:', error);
      handleDisconnect(ws);
    });
  });

  server.listen(config.port, () => {
    console.log(`
╔══════════════════════════════════════════╗
║        Walkie Talkie Server              ║
║──────────────────────────────────────────║
║  WebSocket: ws://localhost:${config.port}        ║
║  Health:    http://localhost:${config.port}/health ║
╚══════════════════════════════════════════╝
    `);
  });
}

main().catch((error) => {
  console.error('Failed to start server:', error);
  process.exit(1);
});
