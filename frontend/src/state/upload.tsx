import { createContext, useCallback, useContext, useEffect, useMemo, useState, type Dispatch, type ReactNode, type SetStateAction } from 'react';
import { ApiError, api } from '../api/client';
import type { UploadResult } from '../api/types';
import { useI18n } from '../i18n';
import { useFeedback } from './feedback';
import { useNickname } from './nickname';

export type UploadPhase =
  | { kind: 'idle' }
  | { kind: 'uploading'; percent: number; name: string }
  | { kind: 'scanning'; name: string }
  | { kind: 'form'; upload: UploadResult };

/**
 * What the user filled in for the uploaded capture.
 *
 * @param fieldStartUs last valid start, shown by the time field (an invalid entry leaves it as is)
 */
export interface RecordDraft {
  name: string;
  description: string;
  categoryId: string;
  ports: number[];
  ip: string;
  startUs: number | null;
  endUs: number | null;
  fieldStartUs: number;
  fieldEndUs: number;
  tagIds: string[];
}

interface UploadState {
  phase: UploadPhase;
  draft: RecordDraft | null;
  /** Uploads and scans the file; keeps running while the user is on another page. */
  start: (file: File) => Promise<void>;
  patch: (changes: Partial<RecordDraft>) => void;
  setTagIds: Dispatch<SetStateAction<string[]>>;
  /** Drops the form and discards the uploaded file on the server. */
  discard: () => void;
  /** The record was saved: drops the form, the file now belongs to the record. */
  finish: () => void;
  /** True while there is work that leaving the page would lose. */
  busy: boolean;
  /** For errors from the upload's calls: an expired upload closes the form with a notice (returns true). */
  closeIfExpired: (e: unknown) => boolean;
}

const CAPTURE_EXTENSION = /\.(pcapng|pcap|cap)$/i;
const Ctx = createContext<UploadState | null>(null);

function newDraft(upload: UploadResult): RecordDraft {
  const { startUs, endUs } = upload.summary;
  return {
    name: upload.fileName.replace(CAPTURE_EXTENSION, ''), description: '', categoryId: '', ports: [], ip: '',
    startUs, endUs, fieldStartUs: startUs, fieldEndUs: endUs, tagIds: [],
  };
}

/** The upload in progress and its record form, kept above the pages so switching pages loses nothing. */
export function UploadProvider({ children }: { children: ReactNode }) {
  const { nickname } = useNickname();
  const { t } = useI18n();
  const { showError, popup } = useFeedback();
  const [phase, setPhase] = useState<UploadPhase>({ kind: 'idle' });
  const [draft, setDraft] = useState<RecordDraft | null>(null);
  const busy = phase.kind !== 'idle';

  // closing or reloading the browser tab would lose the upload
  useEffect(() => {
    if (!busy) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [busy]);

  const start = useCallback(async (file: File) => {
    setPhase({ kind: 'uploading', percent: 0, name: file.name });

    try {
      const uploaded = await api.upload(file, nickname, (fraction) =>
        setPhase(fraction >= 1 ? { kind: 'scanning', name: file.name } : { kind: 'uploading', percent: Math.round(fraction * 100), name: file.name }));
      setDraft(newDraft(uploaded));
      setPhase({ kind: 'form', upload: uploaded });
    } catch (e) {
      setPhase({ kind: 'idle' });
      showError(e);
    }
  }, [nickname, showError]);

  const patch = useCallback((changes: Partial<RecordDraft>) => setDraft((current) => (current ? { ...current, ...changes } : current)), []);

  const setTagIds = useCallback<Dispatch<SetStateAction<string[]>>>((action) => setDraft((current) => (current
    ? { ...current, tagIds: typeof action === 'function' ? action(current.tagIds) : action }
    : current)), []);

  const finish = useCallback(() => {
    setDraft(null);
    setPhase({ kind: 'idle' });
  }, []);

  const discard = useCallback(() => {
    if (phase.kind === 'form') void api.discardUpload(phase.upload.uploadId).catch(() => undefined);
    finish();
  }, [phase, finish]);

  const closeIfExpired = useCallback((e: unknown) => {
    if (!(e instanceof ApiError && e.code === 'UPLOAD_EXPIRED')) return false;
    finish();
    popup(t('upload.expiredTitle'), t('err.UPLOAD_EXPIRED'), 'warning');
    return true;
  }, [finish, popup, t]);

  const value = useMemo<UploadState>(() => ({ phase, draft, start, patch, setTagIds, discard, finish, busy, closeIfExpired }),
    [phase, draft, start, patch, setTagIds, discard, finish, busy, closeIfExpired]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useUpload(): UploadState {
  const state = useContext(Ctx);
  if (!state) throw new Error('useUpload outside provider');
  return state;
}
