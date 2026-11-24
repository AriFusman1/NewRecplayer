import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { loadPref, savePref } from '../util/prefs';

interface NicknameState {
  nickname: string;
  setNickname: (name: string) => void;
}

const Ctx = createContext<NicknameState | null>(null);

/** The nickname lives in this browser's localStorage; no login. */
export function NicknameProvider({ children }: { children: ReactNode }) {
  const [nickname, setState] = useState(() => loadPref('nickname') ?? '');
  const setNickname = useCallback((name: string) => {
    const trimmed = name.trim();
    savePref('nickname', trimmed);
    setState(trimmed);
  }, []);
  return <Ctx.Provider value={{ nickname, setNickname }}>{children}</Ctx.Provider>;
}

export function useNickname(): NicknameState {
  const state = useContext(Ctx);
  if (!state) throw new Error('useNickname outside provider');
  return state;
}
