import type { Session } from './types';

export type LiveMessage =
  | { type: 'sessions'; sessions: Session[] }
  | { type: 'notice'; level: 'error' | 'warning' | 'info'; code: string; params: Record<string, unknown>; sessionId: string; startedBy: string }
  | { type: 'catalog' }
  | { type: 'history' };

/** Plain WebSocket to /ws with automatic reconnect. Returns a function that closes it. */
export function connectLive(onMessage: (m: LiveMessage) => void, onStatus: (connected: boolean) => void): () => void {
  let ws: WebSocket | null = null;
  let closed = false;
  let retry = 500;
  let timer: number | undefined;

  const open = () => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${proto}//${location.host}/ws`);
    ws.onopen = () => {
      retry = 500;
      onStatus(true);
    };
    ws.onmessage = (e) => {
      try {
        onMessage(JSON.parse(e.data));
      } catch {
        /* ignore malformed */
      }
    };
    ws.onclose = () => {
      onStatus(false);
      if (closed) return;
      timer = window.setTimeout(open, retry);
      retry = Math.min(retry * 2, 5000);
    };
  };
  open();
  return () => {
    closed = true;
    window.clearTimeout(timer);
    ws?.close();
  };
}
