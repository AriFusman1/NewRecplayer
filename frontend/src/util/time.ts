// All user-facing times are Israel time (Asia/Jerusalem), "DD/MM/YYYY HH:MM:SS".
// Internally everything is epoch microseconds; conversion happens only here.
// Time zones and daylight saving are handled by date-fns + @date-fns/tz.

import { TZDate } from '@date-fns/tz';
import { addDays, addHours, addMinutes, addSeconds, format, isValid, parse } from 'date-fns';
import prettyBytes from 'pretty-bytes';

const TZ = 'Asia/Jerusalem';
const DISPLAY = 'dd/MM/yyyy HH:mm:ss';

export const MS_PER_SECOND = 1000;
const US_PER_MS = 1000;
const US_PER_SECOND = US_PER_MS * MS_PER_SECOND;
const SECONDS_PER_MINUTE = 60;
const SECONDS_PER_HOUR = 3600;

export function formatMs(epochMs: number): string {
  return format(new TZDate(epochMs, TZ), DISPLAY);
}

export function formatUs(epochUs: number): string {
  return formatMs(Math.floor(epochUs / US_PER_MS));
}

/** A calendar day and an optional wall-clock time, as the date/time picker edits them (no time zone). */
export interface DateTimeParts {
  year: number;
  month: number;
  day: number;
  time: { hour: number; minute: number; second: number } | null;
}

const PARTS = /^(\d{1,2})\/(\d{1,2})\/(\d{4})(?:\s+(\d{1,2})(?::(\d{2})(?::(\d{2}))?)?)?$/;

/** Reads "D/M/YYYY", "D/M/YYYY H", "D/M/YYYY H:MM" or "D/M/YYYY H:MM:SS"; null if it is not a real date and time. */
export function readDateTime(text: string): DateTimeParts | null {
  const match = PARTS.exec(text.trim().replace(/\s+/g, ' '));
  if (!match) return null;
  const [day, month, year] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const check = new Date(year, month - 1, day);
  if (check.getFullYear() !== year || check.getMonth() !== month - 1 || check.getDate() !== day) return null;
  if (match[4] === undefined) return { year, month, day, time: null };
  const time = { hour: Number(match[4]), minute: Number(match[5] ?? 0), second: Number(match[6] ?? 0) };
  if (time.hour > 23 || time.minute > 59 || time.second > 59) return null;
  return { year, month, day, time };
}

/** "DD/MM/YYYY", plus " HH:MM:SS" when there is a time. */
export function writeDateTime(parts: DateTimeParts): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const date = `${pad(parts.day)}/${pad(parts.month)}/${parts.year}`;
  return parts.time ? `${date} ${pad(parts.time.hour)}:${pad(parts.time.minute)}:${pad(parts.time.second)}` : date;
}

/** The Israel-time calendar day of an epoch µs (no time). */
export function usToDay(epochUs: number): DateTimeParts {
  const date = new TZDate(Math.floor(epochUs / US_PER_MS), TZ);
  return { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(), time: null };
}

/** Today's date in Israel time, for a picker that starts empty. */
export function todayParts(): DateTimeParts {
  return usToDay(Date.now() * US_PER_MS);
}

/** Orders calendar days (the time is ignored): negative if `left` is the earlier day. */
export function compareDays(left: DateTimeParts, right: DateTimeParts): number {
  return (left.year - right.year) * 10_000 + (left.month - right.month) * 100 + (left.day - right.day);
}

/** What a filter bound may name, most precise first. */
const FILTER_INPUTS = [
  { pattern: 'd/M/yyyy H:mm:ss', next: (date: Date) => addSeconds(date, 1) },
  { pattern: 'd/M/yyyy H:mm', next: (date: Date) => addMinutes(date, 1) },
  { pattern: 'd/M/yyyy H', next: (date: Date) => addHours(date, 1) },
  { pattern: 'd/M/yyyy', next: (date: Date) => addDays(date, 1) },
];

/**
 * A typed range bound (Israel time) as epoch µs: a date, or a date with HH, HH:MM or HH:MM:SS. A
 * start bound is the beginning of what it names (a date alone = 00:00:00); an end bound covers the
 * whole day, hour, minute or second it names (a date alone = through 23:59:59). Null if invalid.
 */
export function parseBoundUs(text: string, isEnd: boolean): number | null {
  // date-fns alone would read "1/1/26" as the year 26
  if (!readDateTime(text)) return null;
  const normalized = text.trim().replace(/\s+/g, ' ');

  for (const { pattern, next } of FILTER_INPUTS) {
    const parsed = parse(normalized, pattern, new TZDate(0, TZ));
    if (!isValid(parsed)) continue;
    return isEnd ? next(parsed).getTime() * US_PER_MS - 1 : parsed.getTime() * US_PER_MS;
  }
  return null;
}

/** "mm:ss", or "h:mm:ss" from one hour. */
export function formatDuration(durationUs: number): string {
  const total = Math.max(0, Math.floor(durationUs / US_PER_SECOND));
  const hours = Math.floor(total / SECONDS_PER_HOUR);
  const minutes = Math.floor((total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE);
  const seconds = total % SECONDS_PER_MINUTE;
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
