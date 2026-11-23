import ReconnectingWebSocket from 'reconnecting-websocket';
import type { Session } from './types';

export type LiveMessage =
  | { type: 'sessions'; sessions: Session[] }
  | { type: 'notice'; level: 'error' | 'warning' | 'info'; code: string; params: Record<string, unknown>; sessionId: string; startedBy: string }
  | { type: 'catalog' }
  | { type: 'history' };

/** WebSocket to /ws that reconnects by itself (0.5 s, doubling up to 5 s). Returns a function that closes it. */
export function connectLive(onMessage: (message: LiveMessage) => void, onStatus: (connected: boolean) => void): () => void {
  const url = () => `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`;
  const socket = new ReconnectingWebSocket(url, [], { minReconnectionDelay: 500, maxReconnectionDelay: 5000, reconnectionDelayGrowFactor: 2 });
  socket.onopen = () => onStatus(true);
  socket.onclose = () => onStatus(false);
  socket.onmessage = (e) => {
    try {
      onMessage(JSON.parse(e.data));
    } catch {
      /* ignore malformed */
    }
  };
  return () => socket.close();
}
