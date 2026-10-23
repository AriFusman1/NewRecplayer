// localStorage access that never throws (private windows, blocked storage).

const PREFIX = 'pcap-player.';

export function loadPref(key: string): string | null {
  try {
    return window.localStorage.getItem(PREFIX + key);
  } catch {
    return null;
  }
}

export function savePref(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(PREFIX + key);
    else window.localStorage.setItem(PREFIX + key, value);
  } catch {
    /* storage unavailable: keep working in memory */
  }
}

export function loadJson<T>(key: string, fallback: T): T {
  const raw = loadPref(key);
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

export function saveJson(key: string, value: unknown): void {
  savePref(key, JSON.stringify(value));
}
