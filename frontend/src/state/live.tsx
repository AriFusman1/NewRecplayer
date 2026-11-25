import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { connectLive } from '../api/live';
import type { Session, Usage } from '../api/types';
import { useI18n } from '../i18n';
import { useFeedback } from './feedback';
import { useNickname } from './nickname';

interface LiveState {
  sessions: Session[];
  /** Replays running now on the server, for all users, out of the maximum allowed. */
  usage: Usage;
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
  const [usage, setUsage] = useState<Usage>({ active: 0, max: 0 });
  const [connected, setConnected] = useState(false);
  const [historySignal, setHistorySignal] = useState(0);
  const { t, tc } = useI18n();
  const { popup, toast } = useFeedback();
  const { nickname } = useNickname();

  // the socket lives for the whole app; read the latest helpers through a ref
  const helpers = useRef({ t, tc, popup, toast, nickname, onCatalogChange });
  helpers.current = { t, tc, popup, toast, nickname, onCatalogChange };

  useEffect(() => connectLive((message) => {
    const latest = helpers.current;
    switch (message.type) {
      case 'sessions':
        setSessions(message.sessions);
        setUsage({ active: message.active, max: message.max });
        break;
      case 'catalog':
        latest.onCatalogChange();
        break;
      case 'history':
        setHistorySignal((signal) => signal + 1);
        break;
      case 'notice': {
        const text = latest.tc('err', message.code, message.params, message.code);
        const title = message.code === 'CLIENT_DISCONNECTED' ? latest.t('notice.CLIENT_DISCONNECTED') : latest.t('notice.failed');
        if (message.startedBy === latest.nickname) latest.popup(title, text, message.level === 'error' ? 'error' : 'warning');
        else latest.toast(message.level === 'error' ? 'error' : 'warning', `${title}: ${text}`);
        break;
      }
    }
  }, (isConnected) => {
    setConnected(isConnected);
    if (isConnected) helpers.current.onCatalogChange(); // resync after reconnect
  }), []);

  const value = useMemo(() => ({ sessions, usage, connected, historySignal }), [sessions, usage, connected, historySignal]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/**
 * Free replay slots on the server (all users together), for disabling Play buttons before the
 * server would refuse. The server checks again on start, so this only has to be close.
 */
export function useRoom() {
  const { usage } = useLive();
  const { t } = useI18n();
  const known = usage.max > 0; // 0 = not received yet: don't block anything
  const free = Math.max(0, usage.max - usage.active);
  /** Why {@code count} more replays cannot start now, or null if they can. */
  const blockedFor = (count: number): string | null => {
    if (!known || count <= free) return null;
    return free === 0 ? t('usage.full', { max: usage.max }) : t('select.noRoom', { free, max: usage.max, n: count });
  };
  return { free, blockedFor };
}

export function useLive(): LiveState {
  const state = useContext(Ctx);
  if (!state) throw new Error('useLive outside provider');
  return state;
}
