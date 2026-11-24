import { useEffect, useState } from 'react';
import { useDebounce } from 'use-debounce';
import { api, type DraftBody } from '../../api/client';
import type { Preview } from '../../api/types';
import { useFeedback } from '../../state/feedback';
import { useUpload } from '../../state/upload';

const PREVIEW_DEBOUNCE_MS = 350;

/**
 * Server-side preview of the record being defined, refreshed (debounced) whenever the draft
 * changes. `draft` null = not enough filled in yet.
 */
export function useDraftPreview(draft: DraftBody | null): { preview: Preview | null; computing: boolean } {
  const { showError } = useFeedback();
  const { closeIfExpired } = useUpload();
  const [preview, setPreview] = useState<Preview | null>(null);
  const [loading, setLoading] = useState(false);
  // compared by value: the caller builds a new draft object on every render
  const key = draft ? JSON.stringify(draft) : null;
  const [settledKey] = useDebounce(key, PREVIEW_DEBOUNCE_MS);

  useEffect(() => {
    if (!settledKey) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api.preview(JSON.parse(settledKey) as DraftBody)
      .then((result) => { if (!cancelled) setPreview(result); })
      .catch((e) => {
        if (cancelled) return;
        setPreview(null);
        if (!closeIfExpired(e)) showError(e);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [settledKey, showError, closeIfExpired]);

  // "computing" also while the user is still typing (the debounce has not settled yet)
  return { preview, computing: loading || (key !== null && key !== settledKey) };
}
