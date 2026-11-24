// Calendar arithmetic for the History date-range picker, in Israel time (Asia/Jerusalem) like the
// rest of the app. Plain functions (no React), so they can be tested on their own.

import { TZDate } from '@date-fns/tz';
import { format } from 'date-fns';

const TZ = 'Asia/Jerusalem';
const DAYS_PER_WEEK = 7;
const END_OF_SECOND_MS = 999;

/** A calendar day; `month` is 0-11 like JavaScript dates. */
export interface Day {
  year: number;
  month: number;
  day: number;
}

export interface TimeRange {
  from: number | null;
  to: number | null;
}

/** The Israel calendar day an instant falls on. */
export function dayOf(epochMs: number): Day {
  const local = new TZDate(epochMs, TZ);
  return { year: local.getFullYear(), month: local.getMonth(), day: local.getDate() };
}

export function sameDay(left: Day, right: Day): boolean {
  return left.year === right.year && left.month === right.month && left.day === right.day;
}

/** Epoch ms of a wall-clock time in Israel ("HH:MM:SS"); null if the time can't be read. */
export function atTime(day: Day, time: string): number | null {
  const match = time.trim().match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!match) return null;
  const [hours, minutes, seconds] = [Number(match[1]), Number(match[2]), Number(match[3] ?? 0)];
  if (hours > 23 || minutes > 59 || seconds > 59) return null;
  return new TZDate(day.year, day.month, day.day, hours, minutes, seconds, TZ).getTime();
}

export const DAY_START = '00:00:00';
export const DAY_END = '23:59:59';

/** The whole of a range of days: from the first day's 00:00:00 to the end of the last day's 23:59:59. */
export function wholeDays(first: Day, last: Day): TimeRange {
  return { from: atTime(first, DAY_START), to: atTime(last, DAY_END)! + END_OF_SECOND_MS };
}

/** Today, in Israel. */
export function today(nowMs: number): TimeRange {
  const day = dayOf(nowMs);
  return wholeDays(day, day);
}

/** The last `count` days including today, in Israel. */
export function lastDays(nowMs: number, count: number): TimeRange {
  const last = dayOf(nowMs);
  const first = new TZDate(last.year, last.month, last.day - (count - 1), 12, 0, 0, TZ); // noon: safe across DST
  return wholeDays(dayOf(first.getTime()), last);
}

/**
 * The weeks shown for a month: Sunday to Saturday (the Israeli week), including the days of the
 * neighbouring months that complete the first and last week.
 */
export function monthGrid(year: number, month: number): Day[][] {
  const first = new TZDate(year, month, 1, 12, 0, 0, TZ);
  const start = new TZDate(year, month, 1 - first.getDay(), 12, 0, 0, TZ);
  const weeks: Day[][] = [];
  let cursor = start;
  do {
    const week: Day[] = [];
    for (let weekday = 0; weekday < DAYS_PER_WEEK; weekday++) {
      week.push({ year: cursor.getFullYear(), month: cursor.getMonth(), day: cursor.getDate() });
      cursor = new TZDate(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() + 1, 12, 0, 0, TZ);
    }
    weeks.push(week);
  } while (cursor.getMonth() === month && cursor.getFullYear() === year);
  return weeks;
}

/** "DD/MM/YYYY" in Israel time. */
export function formatDay(epochMs: number): string {
  return format(new TZDate(epochMs, TZ), 'dd/MM/yyyy');
}

/** "HH:MM:SS" in Israel time. */
export function formatTime(epochMs: number): string {
  return format(new TZDate(epochMs, TZ), 'HH:mm:ss');
}

/** A short label for a range: "24/11/2026 – 26/11/2026", times only when not whole days. */
export function formatRange({ from, to }: TimeRange): string {
  const show = (ms: number, defaultTime: string) =>
    formatTime(ms) === defaultTime ? formatDay(ms) : `${formatDay(ms)} ${formatTime(ms)}`;
  if (from !== null && to !== null) return `${show(from, DAY_START)} – ${show(to, DAY_END)}`;
  if (from !== null) return `≥ ${show(from, DAY_START)}`;
  if (to !== null) return `≤ ${show(to, DAY_END)}`;
  return '';
}
