import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { connectLive } from '../api/live';
import type { Session } from '../api/types';
import { useI18n } from '../i18n';
import { useFeedback } from './feedback';
import { useNickname } from './nickname';

interface LiveState {
  sessions: Session[];
  connected: boolean;
  /** Increments whenever the history changes on the server. */
  historySignal: number;
}

const Ctx = createContext<LiveState | null>(null);

/**
 * Holds the WebSocket. Every viewer sees every running replay; failures pop up for the person who
 * started the run and show as a toast for everyone else.
 */
export function LiveProvider({ children, onCatalogChange }: { children: ReactNode; onCatalogChange: () => void }) {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [connected, setConnected] = useState(false);
  const [historySignal, setHistorySignal] = useState(0);
  const { t, tc } = useI18n();
  const { popup, toast } = useFeedback();
  const { nickname } = useNickname();

  // the socket lives for the whole app; read the latest helpers through a ref
  const helpers = useRef({ t, tc, popup, toast, nickname, onCatalogChange });
  helpers.current = { t, tc, popup, toast, nickname, onCatalogChange };

  useEffect(() => connectLive((m) => {
    const h = helpers.current;
    switch (m.type) {
      case 'sessions':
        setSessions(m.sessions);
        break;
      case 'catalog':
        h.onCatalogChange();
        break;
      case 'history':
        setHistorySignal((n) => n + 1);
        break;
      case 'notice': {
        const text = h.tc('err', m.code, m.params, m.code);
        const title = m.code === 'CLIENT_DISCONNECTED' ? h.t('notice.CLIENT_DISCONNECTED') : h.t('notice.failed');
        if (m.startedBy === h.nickname) h.popup(title, text, m.level === 'error' ? 'error' : 'warning');
        else h.toast(m.level === 'error' ? 'error' : 'warning', `${title}: ${text}`);
        break;
      }
    }
  }, (c) => {
    setConnected(c);
    if (c) helpers.current.onCatalogChange(); // resync after reconnect
  }), []);

  return <Ctx.Provider value={{ sessions, connected, historySignal }}>{children}</Ctx.Provider>;
}

export function useLive(): LiveState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useLive outside provider');
  return v;
}
