import i18next from 'i18next';
import { useCallback, useEffect, useMemo, type ReactNode } from 'react';
import { I18nextProvider, initReactI18next, useTranslation } from 'react-i18next';
import { formatUs } from '../util/time';
import { loadPref, savePref } from '../util/prefs';
import { en, type MessageKey } from './en';
import { he } from './he';

// Translations run on i18next. This module only adds what the app needs on top of it: typed keys
// (a missing or misspelled key fails the build), server-code lookup (tc), friendly formatting of
// parameters, the saved language choice and the page direction.

export type Lang = 'en' | 'he';
type Params = Record<string, unknown>;

interface I18n {
  lang: Lang;
  dir: 'ltr' | 'rtl';
  setLang: (language: Lang) => void;
  /** Translate a known key. */
  t: (key: MessageKey, params?: Params) => string;
  /** Translate a server code such as "TCP_GAPS" under a prefix ("warn", "err", "log"); falls back to `fallback`. */
  tc: (prefix: 'warn' | 'err' | 'log' | 'notice', code: string, params?: Params, fallback?: string) => string;
}

const i18n = i18next.createInstance();
void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, he: { translation: he } },
  lng: loadPref('lang') === 'he' ? 'he' : 'en',
  fallbackLng: 'en',
  initAsync: false,
  // keys are flat strings like "upload.drop", not nested objects
  keySeparator: false,
  nsSeparator: false,
  // placeholders are {name}, not i18next's default {{name}}
  interpolation: { prefix: '{', suffix: '}', escapeValue: false },
  missingInterpolationHandler: () => '',
});

/** Params shown with thousands separators (never ports or ids). */
const GROUPED = new Set(['count', 'bytes', 'n']);

/**
 * Makes server values readable: "startUs": 1700000000000000 is also offered as "start":
 * "14/11/2023 22:13:20"; counts get thousands separators; lists are joined with commas.
 */
function readable(params?: Params): Params | undefined {
  if (!params) return undefined;
  const formatted: Params = { ...params };
  for (const [key, value] of Object.entries(params)) {
    if (key.endsWith('Us') && typeof value === 'number') formatted[key.slice(0, -2)] = formatUs(value);
    if (typeof value === 'number' && GROUPED.has(key)) formatted[key] = value.toLocaleString('en-US');
    if (Array.isArray(value)) formatted[key] = value.join(', ');
    if (value === null) formatted[key] = '';
  }
  return formatted;
}

export function I18nProvider({ children }: { children: ReactNode }) {
  return <I18nextProvider i18n={i18n}><PageLanguage />{children}</I18nextProvider>;
}

/** Keeps <html lang> and <html dir> in step with the chosen language (Hebrew is right-to-left). */
function PageLanguage() {
  const { lang, dir } = useI18n();
  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = dir;
  }, [lang, dir]);
  return null;
}

export function useI18n(): I18n {
  const { t: translate, i18n: instance } = useTranslation();
  const lang: Lang = instance.language === 'he' ? 'he' : 'en';
  const dir = instance.dir(lang) === 'rtl' ? 'rtl' : 'ltr';

  const setLang = useCallback((language: Lang) => {
    savePref('lang', language);
    void instance.changeLanguage(language);
  }, [instance]);

  return useMemo<I18n>(() => ({
    lang,
    dir,
    setLang,
    t: (key, params) => translate(key, readable(params)),
    tc: (prefix, code, params, fallback) => {
      const key = `${prefix}.${code}`;
      return instance.exists(key) ? translate(key, readable(params)) : fallback ?? code;
    },
  }), [lang, dir, setLang, translate, instance]);
}

export type { MessageKey };
