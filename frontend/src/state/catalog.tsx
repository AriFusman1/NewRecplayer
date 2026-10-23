import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { Catalog, Category, FileView, PcapRecord, Tag } from '../api/types';

interface CatalogState {
  catalog: Catalog;
  loaded: boolean;
  reload: () => Promise<void>;
  categoryById: Map<string, Category>;
  tagById: Map<string, Tag>;
  fileById: Map<string, FileView>;
  recordById: Map<string, PcapRecord>;
}

const EMPTY: Catalog = { categories: [], tags: [], records: [], files: [] };
const Ctx = createContext<CatalogState | null>(null);

/** Categories, tags, records and files; refetched whenever the server announces a change. */
export function CatalogProvider({ children, changeSignal }: { children: ReactNode; changeSignal: number }) {
  const [catalog, setCatalog] = useState<Catalog>(EMPTY);
  const [loaded, setLoaded] = useState(false);

  const reload = useCallback(async () => {
    try {
      setCatalog(await api.catalog());
      setLoaded(true);
    } catch {
      /* server not ready yet; the next change signal or reconnect retries */
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload, changeSignal]);

  const value = useMemo<CatalogState>(() => ({
    catalog,
    loaded,
    reload,
    categoryById: new Map(catalog.categories.map((c) => [c.id, c])),
    tagById: new Map(catalog.tags.map((t) => [t.id, t])),
    fileById: new Map(catalog.files.map((f) => [f.id, f])),
    recordById: new Map(catalog.records.map((r) => [r.id, r])),
  }), [catalog, loaded, reload]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useCatalog(): CatalogState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useCatalog outside provider');
  return v;
}
