// All user-facing times are Israel time (Asia/Jerusalem), "DD/MM/YYYY HH:MM:SS".
// Internally everything is epoch microseconds; conversion happens only here.
// Time zones and daylight saving are handled by date-fns + @date-fns/tz.

import { TZDate } from '@date-fns/tz';
import { format, isValid, parse } from 'date-fns';
import prettyBytes from 'pretty-bytes';

const TZ = 'Asia/Jerusalem';
const DISPLAY = 'dd/MM/yyyy HH:mm:ss';
/** Accepts 1- or 2-digit day, month and hour when typing. */
const INPUT = 'd/M/yyyy H:mm:ss';

export function formatMs(epochMs: number): string {
  return format(new TZDate(epochMs, TZ), DISPLAY);
}

export function formatUs(epochUs: number): string {
  return formatMs(Math.floor(epochUs / 1000));
}

/** Parses "DD/MM/YYYY HH:MM:SS" (Israel time) to epoch ms; null if invalid (e.g. 31/02). */
export function parseMs(text: string): number | null {
  const normalized = text.trim().replace(/\s+/g, ' ');
  // the reference date makes date-fns read the wall-clock time in Israel time
  const parsed = parse(normalized, INPUT, new TZDate(0, TZ));
  return isValid(parsed) ? parsed.getTime() : null;
}

/** "mm:ss", or "h:mm:ss" from one hour. */
export function formatDuration(durationUs: number): string {
  const total = Math.max(0, Math.floor(durationUs / 1_000_000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}

/** "1.5 MB" (decimal units, as file managers show). */
export function formatBytes(bytes: number): string {
  return prettyBytes(bytes);
}

export function formatNumber(value: number): string {
  return value.toLocaleString('en-US');
}
