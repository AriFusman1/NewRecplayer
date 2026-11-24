// Run with `npm test` (Node's built-in test runner; no test library needed).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { atTime, dayOf, formatDay, formatRange, formatTime, lastDays, monthGrid, today, wholeDays } from './dateRange.js';

// 24/11/2026 10:30 in Israel is 08:30 UTC (winter time, UTC+2)
const NOW = Date.UTC(2026, 10, 24, 8, 30, 0);

test('days are Israel calendar days', () => {
  assert.deepEqual(dayOf(NOW), { year: 2026, month: 10, day: 24 });
  // 23:30 UTC on the 24th is already the 25th in Israel
  assert.deepEqual(dayOf(Date.UTC(2026, 10, 24, 23, 30)), { year: 2026, month: 10, day: 25 });
});

test('Today and Last 7 days cover whole Israel days', () => {
  const day = today(NOW);
  assert.equal(formatDay(day.from!) + ' ' + formatTime(day.from!), '24/11/2026 00:00:00');
  assert.equal(formatDay(day.to!) + ' ' + formatTime(day.to!), '24/11/2026 23:59:59');
  const week = lastDays(NOW, 7);
  assert.equal(formatDay(week.from!), '18/11/2026');
  assert.equal(formatDay(week.to!), '24/11/2026');
});

test('times are read as Israel wall-clock time', () => {
  const day = { year: 2026, month: 10, day: 24 };
  assert.equal(atTime(day, '10:30:00'), NOW);
  assert.equal(atTime(day, '10:30'), NOW);
  assert.equal(atTime(day, '25:00'), null);
  assert.equal(atTime(day, 'noon'), null);
  // summer time (UTC+3)
  assert.equal(atTime({ year: 2026, month: 6, day: 1 }, '12:00:00'), Date.UTC(2026, 6, 1, 9, 0, 0));
});

test('the month grid runs Sunday to Saturday and covers the whole month', () => {
  const weeks = monthGrid(2026, 10); // November 2026 starts on a Sunday
  assert.deepEqual(weeks[0][0], { year: 2026, month: 10, day: 1 });
  assert.ok(weeks.every((week) => week.length === 7));
  const days = weeks.flat().filter((day) => day.month === 10).map((day) => day.day);
  assert.deepEqual(days, Array.from({ length: 30 }, (_, index) => index + 1));
  const february = monthGrid(2026, 1); // 1/2/2026 is a Sunday as well
  assert.equal(february.flat().filter((day) => day.month === 1).length, 28);
  const december = monthGrid(2026, 11); // 1/12/2026 is a Tuesday: two days of November first
  assert.deepEqual(december[0].slice(0, 3).map((day) => day.day), [29, 30, 1]);
});

test('range labels: whole days without times, otherwise with them', () => {
  const nov = (day: number) => ({ year: 2026, month: 10, day });
  assert.equal(formatRange(wholeDays(nov(24), nov(26))), '24/11/2026 – 26/11/2026');
  assert.equal(formatRange({ from: atTime(nov(24), '10:00:00'), to: null }), '≥ 24/11/2026 10:00:00');
  assert.equal(formatRange({ from: null, to: null }), '');
});
