import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { Catalog, Category, FileView, Mode, PcapRecord, Tag } from '../api/types';

interface CatalogState {
  catalog: Catalog;
  loaded: boolean;
  /** Refetches the catalog; resolves to the fresh catalog, or null if the server did not answer. */
  reload: () => Promise<Catalog | null>;
  categoryById: Map<string, Category>;
  tagById: Map<string, Tag>;
  fileById: Map<string, FileView>;
  recordById: Map<string, PcapRecord>;
  /** The connection mode of a record (from its category), or undefined if either is gone. */
  modeOf: (recordId: string) => Mode | undefined;
}

const EMPTY: Catalog = { categories: [], tags: [], records: [], files: [], playlists: [] };
const Ctx = createContext<CatalogState | null>(null);

/** Categories, tags, records and files; refetched whenever the server announces a change. */
export function CatalogProvider({ children, changeSignal }: { children: ReactNode; changeSignal: number }) {
  const [catalog, setCatalog] = useState<Catalog>(EMPTY);
  const [loaded, setLoaded] = useState(false);

  /** Reloads overlap (change signals, explicit reloads); only a response newer than the shown one is applied. */
  const requested = useRef(0);
  const applied = useRef(0);

  const reload = useCallback(async (): Promise<Catalog | null> => {
    const request = ++requested.current;

    try {
      const fetched = await api.catalog();
      const fresh = { ...fetched, playlists: fetched.playlists ?? [] };

      if (request > applied.current) {
        applied.current = request;
        setCatalog(fresh);
        setLoaded(true);
      }
      return fresh;
    } catch {
      /* server not ready yet; the next change signal or reconnect retries */
      return null;
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload, changeSignal]);

  const value = useMemo<CatalogState>(() => {
    const categoryById = new Map(catalog.categories.map((category) => [category.id, category]));
    const recordById = new Map(catalog.records.map((record) => [record.id, record]));
    return {
      catalog,
      loaded,
      reload,
      categoryById,
      tagById: new Map(catalog.tags.map((tag) => [tag.id, tag])),
      fileById: new Map(catalog.files.map((file) => [file.id, file])),
      recordById,
      modeOf: (recordId) => {
        const record = recordById.get(recordId);
        return record ? categoryById.get(record.categoryId)?.mode : undefined;
      },
    };
  }, [catalog, loaded, reload]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useCatalog(): CatalogState {
  const state = useContext(Ctx);
  if (!state) throw new Error('useCatalog outside provider');
  return state;
}
