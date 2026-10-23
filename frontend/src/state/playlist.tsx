import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import type { ItemRequest, Mode } from '../api/types';
import { loadJson, saveJson } from '../util/prefs';

/** One entry of the playlist being built. `key` is local only (records can repeat). */
export interface DraftItem extends ItemRequest {
  key: string;
}

interface PlaylistState {
  items: DraftItem[];
  add: (recordId: string, mode: Mode) => void;
  remove: (key: string) => void;
  move: (from: number, to: number) => void;
  update: (key: string, patch: Partial<ItemRequest>) => void;
  clear: () => void;
}

const Ctx = createContext<PlaylistState | null>(null);
const STORAGE_KEY = 'playlistDraft';
const DEFAULT_PORT = 5000;

export function newKey(): string {
  return Math.random().toString(36).slice(2, 10);
}

export function defaultItem(recordId: string, mode: Mode): DraftItem {
  return { key: newKey(), recordId, host: mode === 'TCP_CLIENT' ? null : '', port: DEFAULT_PORT, sleepAfterMs: 0 };
}

/** The playlist draft is not stored on the server; localStorage keeps it across refreshes. */
export function PlaylistProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<DraftItem[]>(() => loadJson<DraftItem[]>(STORAGE_KEY, []));

  useEffect(() => saveJson(STORAGE_KEY, items), [items]);

  const add = useCallback((recordId: string, mode: Mode) => setItems((l) => [...l, defaultItem(recordId, mode)]), []);
  const remove = useCallback((key: string) => setItems((l) => l.filter((x) => x.key !== key)), []);
  const move = useCallback((from: number, to: number) => setItems((l) => moveItem(l, from, to)), []);
  const update = useCallback((key: string, patch: Partial<ItemRequest>) =>
    setItems((l) => l.map((x) => (x.key === key ? { ...x, ...patch } : x))), []);
  const clear = useCallback(() => setItems([]), []);

  return <Ctx.Provider value={{ items, add, remove, move, update, clear }}>{children}</Ctx.Provider>;
}

export function moveItem<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || from >= list.length) return list;
  const copy = [...list];
  const [x] = copy.splice(from, 1);
  copy.splice(Math.max(0, Math.min(to, copy.length)), 0, x);
  return copy;
}

export function usePlaylist(): PlaylistState {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePlaylist outside provider');
  return v;
}
