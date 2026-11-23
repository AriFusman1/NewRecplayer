import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import type { ItemRequest, Mode } from '../api/types';
import { loadJson, saveJson } from '../util/prefs';
import { suggestTarget } from '../util/targetMemory';

/** One entry of the playlist being built. `key` is local only (records can repeat). */
export interface DraftItem extends ItemRequest {
  key: string;
}

interface PlaylistState {
  items: DraftItem[];
  /** The saved playlist this draft was loaded from / last saved as, or null for an unsaved draft. */
  savedId: string | null;
  setSavedId: (id: string | null) => void;
  /** Replaces the draft with a saved playlist's items. */
  load: (id: string, items: ItemRequest[]) => void;
  add: (recordId: string, mode: Mode) => void;
  addMany: (list: { recordId: string; mode: Mode }[]) => void;
  remove: (key: string) => void;
  move: (from: number, to: number) => void;
  update: (key: string, patch: Partial<ItemRequest>) => void;
  clear: () => void;
}

const Ctx = createContext<PlaylistState | null>(null);
const STORAGE_KEY = 'playlistDraft';
const SAVED_KEY = 'playlistSavedId';

function newKey(): string {
  return Math.random().toString(36).slice(2, 10);
}

/** A new item aimed where this record was last played (or at your last IP). */
function defaultItem(recordId: string, mode: Mode): DraftItem {
  return { key: newKey(), recordId, ...suggestTarget(recordId, mode), sleepAfterMs: 0 };
}

/** The playlist draft is not stored on the server; localStorage keeps it across refreshes. */
export function PlaylistProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<DraftItem[]>(() => loadJson<DraftItem[]>(STORAGE_KEY, []));

  const [savedId, setSavedId] = useState<string | null>(() => loadJson<string | null>(SAVED_KEY, null));

  useEffect(() => saveJson(STORAGE_KEY, items), [items]);
  useEffect(() => saveJson(SAVED_KEY, savedId), [savedId]);

  const add = useCallback((recordId: string, mode: Mode) => setItems((current) => [...current, defaultItem(recordId, mode)]), []);
  const addMany = useCallback((list: { recordId: string; mode: Mode }[]) =>
    setItems((current) => [...current, ...list.map((entry) => defaultItem(entry.recordId, entry.mode))]), []);
  const load = useCallback((id: string, list: ItemRequest[]) => {
    setItems(list.map((item) => ({ ...item, key: newKey() })));
    setSavedId(id);
  }, []);
  const remove = useCallback((key: string) => setItems((current) => current.filter((item) => item.key !== key)), []);
  const move = useCallback((from: number, to: number) => setItems((current) => moveItem(current, from, to)), []);
  const update = useCallback((key: string, patch: Partial<ItemRequest>) =>
    setItems((current) => current.map((item) => (item.key === key ? { ...item, ...patch } : item))), []);
  const clear = useCallback(() => { setItems([]); setSavedId(null); }, []);

  return <Ctx.Provider value={{ items, savedId, setSavedId, load, add, addMany, remove, move, update, clear }}>{children}</Ctx.Provider>;
}

export function moveItem<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || from >= list.length) return list;
  const copy = [...list];
  const [moved] = copy.splice(from, 1);
  copy.splice(Math.max(0, Math.min(to, copy.length)), 0, moved);
  return copy;
}

export function usePlaylist(): PlaylistState {
  const state = useContext(Ctx);
  if (!state) throw new Error('usePlaylist outside provider');
  return state;
}
