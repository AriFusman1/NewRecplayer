import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { en, type MessageKey } from './en';
import { he } from './he';
import { formatUs } from '../util/time';
import { loadPref, savePref } from '../util/prefs';

// A tiny i18n module: two dictionaries, {placeholder} interpolation, and the document direction.

export type Lang = 'en' | 'he';
type Params = Record<string, unknown>;

const DICTS: Record<Lang, Record<MessageKey, string>> = { en, he };

interface I18n {
  lang: Lang;
  dir: 'ltr' | 'rtl';
  setLang: (l: Lang) => void;
  /** Translate a known key. */
  t: (key: MessageKey, params?: Params) => string;
  /** Translate a server code such as "TCP_GAPS" under a prefix ("warn", "err", "log"); falls back to `fallback`. */
  tc: (prefix: 'warn' | 'err' | 'log' | 'notice', code: string, params?: Params, fallback?: string) => string;
}

const Ctx = createContext<I18n | null>(null);

/** Params shown with thousands separators (never ports or ids). */
const GROUPED = new Set(['count', 'bytes', 'n']);

function interpolate(template: string, params?: Params): string {
  if (!params) return template;
  const p: Params = { ...params };
  // "startUs": 1700000000000000 is also offered as "start": "14/11/2023 22:13:20"
  for (const [k, v] of Object.entries(params)) {
    if (k.endsWith('Us') && typeof v === 'number') p[k.slice(0, -2)] = formatUs(v);
    if (typeof v === 'number' && GROUPED.has(k)) p[k] = v.toLocaleString('en-US');
    if (Array.isArray(v)) p[k] = v.join(', ');
  }
  return template.replace(/\{(\w+)\}/g, (_, name) => (p[name] === undefined || p[name] === null ? '' : String(p[name])));
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(() => (loadPref('lang') === 'he' ? 'he' : 'en'));
  const dir = lang === 'he' ? 'rtl' : 'ltr';

  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = dir;
  }, [lang, dir]);

  const setLang = useCallback((l: Lang) => {
    savePref('lang', l);
    setLangState(l);
  }, []);

  const value = useMemo<I18n>(() => {
    const dict = DICTS[lang];
    const t = (key: MessageKey, params?: Params) => interpolate(dict[key] ?? en[key] ?? key, params);
    const tc = (prefix: string, code: string, params?: Params, fallback?: string) => {
      const key = `${prefix}.${code}` as MessageKey;
      const template = dict[key] ?? en[key];
      return template ? interpolate(template, params) : fallback ?? code;
    };
    return { lang, dir, setLang, t, tc } as I18n;
  }, [lang, dir, setLang]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useI18n(): I18n {
  const v = useContext(Ctx);
  if (!v) throw new Error('useI18n outside I18nProvider');
  return v;
}

export type { MessageKey };
