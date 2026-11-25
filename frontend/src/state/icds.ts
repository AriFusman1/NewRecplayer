import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { IcdSummary } from '../api/types';
import { useCatalog } from './catalog';

/**
 * The ICDs the server knows. Reloaded when the catalog changes (a category's ICD changed, so
 * "used by" changed) and after ICD uploads and deletes ({@code reload}).
 */
export function useIcds() {
  const { catalog } = useCatalog();
  const [icds, setIcds] = useState<IcdSummary[]>([]);

  const reload = useCallback(async () => {
    try {
      setIcds(await api.icds());
    } catch {
      // the list stays as it was; actions on it report their own errors
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload, catalog]);

  return { icds, reload };
}
