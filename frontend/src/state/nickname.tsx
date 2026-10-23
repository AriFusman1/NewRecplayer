import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { loadPref, savePref } from '../util/prefs';

interface NicknameState {
  nickname: string;
  setNickname: (n: string) => void;
}

const Ctx = createContext<NicknameState | null>(null);

/** The nickname lives in this browser's localStorage; no login. */
export function NicknameProvider({ children }: { children: ReactNode }) {
  const [nickname, setState] = useState(() => loadPref('nickname') ?? '');
  const setNickname = useCallback((n: string) => {
    const v = n.trim();
    savePref('nickname', v);
    setState(v);
  }, []);
  return <Ctx.Provider value={{ nickname, setNickname }}>{children}</Ctx.Provider>;
}

export function useNickname(): NicknameState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useNickname outside provider');
  return v;
}
