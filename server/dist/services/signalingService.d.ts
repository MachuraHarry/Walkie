import { WebSocket } from 'ws';
import { SignalMessage } from '../websocket/types';
export declare function registerClient(username: string, ws: WebSocket): void;
export declare function unregisterClient(username: string): void;
export declare function addUserToChannel(username: string, channelId: number): void;
export declare function removeUserFromChannel(username: string, channelId: number): void;
export declare function getUsersInChannel(channelId: number): string[];
export declare function sendToUser(username: string, message: any): void;
export declare function broadcastToChannel(channelId: number, message: any, excludeUsername?: string): void;
export declare function handleSignal(signal: SignalMessage): void;
export declare function getOnlineUsers(): string[];
//# sourceMappingURL=signalingService.d.ts.map