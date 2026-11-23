import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { useI18n } from '../i18n';

export type Level = 'error' | 'warning' | 'info' | 'success';

interface Toast {
  id: number;
  level: Level;
  text: string;
}

interface Popup {
  title: string;
  text: string;
  level: Level;
}

interface Feedback {
  /** A short message in the corner that disappears by itself. */
  toast: (level: Level, text: string) => void;
  /** A blocking popup the user has to dismiss. */
  popup: (title: string, text: string, level?: Level) => void;
  /** Shows any thrown error as a popup, translated when it is an ApiError. */
  showError: (e: unknown) => void;
  toasts: Toast[];
  current: Popup | null;
  dismissPopup: () => void;
  dismissToast: (id: number) => void;
}

const Ctx = createContext<Feedback | null>(null);

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const { t, tc } = useI18n();
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [popups, setPopups] = useState<Popup[]>([]);
  const nextId = useRef(1);

  const dismissToast = useCallback((id: number) => setToasts((current) => current.filter((toast) => toast.id !== id)), []);

  const toast = useCallback((level: Level, text: string) => {
    const id = nextId.current++;
    setToasts((current) => [...current.slice(-4), { id, level, text }]);
    window.setTimeout(() => dismissToast(id), level === 'error' ? 8000 : 4000);
  }, [dismissToast]);

  const popup = useCallback((title: string, text: string, level: Level = 'error') => {
    setPopups((current) => [...current, { title, text, level }]);
  }, []);

  const showError = useCallback((e: unknown) => {
    if (e instanceof ApiError) {
      // validation answers carry all problems; show each on its own line
      const errors = (e.params?.errors as { code: string; params: Record<string, unknown> }[] | undefined) ?? null;
      const text = errors
        ? errors.map((problem) => tc('err', problem.code, problem.params)).join('\n')
        : tc('err', e.code, { ...e.params, message: e.message }, e.message);
      popup(t('err.title'), text);
    } else {
      popup(t('err.title'), String((e as Error)?.message ?? e));
    }
  }, [popup, t, tc]);

  const dismissPopup = useCallback(() => setPopups((current) => current.slice(1)), []);

  return (
    <Ctx.Provider value={{ toast, popup, showError, toasts, current: popups[0] ?? null, dismissPopup, dismissToast }}>
      {children}
    </Ctx.Provider>
  );
}

export function useFeedback(): Feedback {
  const feedback = useContext(Ctx);
  if (!feedback) throw new Error('useFeedback outside provider');
  return feedback;
}

/**
 * Runs a server action with the shared error handling: errors pop up translated, an optional
 * success toast is shown, and `busy` is true while it runs.
 *
 * @returns from `run`: true if the action succeeded
 */
export function useAction() {
  const { showError, toast } = useFeedback();
  const [busy, setBusy] = useState(false);
  const run = useCallback(async (action: () => Promise<unknown>, success?: string): Promise<boolean> => {
    setBusy(true);
    try {
      await action();
      if (success) toast('success', success);
      return true;
    } catch (e) {
      showError(e);
      return false;
    } finally {
      setBusy(false);
    }
  }, [showError, toast]);
  return { run, busy };
}
