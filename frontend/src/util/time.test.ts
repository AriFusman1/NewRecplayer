// Run with `npm test` (Node's built-in test runner; no test library needed).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compareDays, formatUs, parseBoundUs, readDateTime, usToDay, writeDateTime, type DateTimeParts } from './time.js';

const US_PER_HOUR = 3_600_000_000;
const day = (year: number, month: number, dayOfMonth: number): DateTimeParts => ({ year, month, day: dayOfMonth, time: null });
/** A bound as Israel time with microseconds, to see exactly where it falls. */
const show = (us: number | null) => (us === null ? null : `${formatUs(us)}.${String(us % 1_000_000).padStart(6, '0')}`);

test('readDateTime: date, hour, minutes and seconds; spaces and 1-digit parts', () => {
  assert.deepEqual(readDateTime('24/11/2026'), day(2026, 11, 24));
  assert.deepEqual(readDateTime('  5/3/2026   7:05 '), { ...day(2026, 3, 5), time: { hour: 7, minute: 5, second: 0 } });
  assert.deepEqual(readDateTime('24/11/2026 12'), { ...day(2026, 11, 24), time: { hour: 12, minute: 0, second: 0 } });
  assert.deepEqual(readDateTime('24/11/2026 12:30:15')?.time, { hour: 12, minute: 30, second: 15 });
});

test('readDateTime: impossible dates and times are rejected', () => {
  assert.equal(readDateTime('31/02/2026'), null);
  assert.equal(readDateTime('29/02/2027'), null);
  assert.equal(readDateTime('29/02/2028')?.day, 29); // leap year
  assert.equal(readDateTime('24/11/2026 24:00'), null);
  assert.equal(readDateTime('24/11/2026 10:60'), null);
  assert.equal(readDateTime('24/11/26'), null); // a 2-digit year is not the year 26
  assert.equal(readDateTime('yesterday'), null);
  assert.equal(readDateTime(''), null);
});

test('writeDateTime pads and adds the time only when there is one', () => {
  assert.equal(writeDateTime(day(2026, 3, 5)), '05/03/2026');
  assert.equal(writeDateTime({ ...day(2026, 3, 5), time: { hour: 7, minute: 5, second: 9 } }), '05/03/2026 07:05:09');
});

test('compareDays orders days and ignores the time', () => {
  assert.ok(compareDays(day(2026, 1, 31), day(2026, 2, 1)) < 0);
  assert.ok(compareDays(day(2027, 1, 1), day(2026, 12, 31)) > 0);
  assert.equal(compareDays({ ...day(2026, 2, 1), time: { hour: 23, minute: 0, second: 0 } }, day(2026, 2, 1)), 0);
});

test('parseBoundUs: a start is the beginning of what it names, an end covers all of it', () => {
  assert.equal(show(parseBoundUs('24/11/2026', false)), '24/11/2026 00:00:00.000000');
  assert.equal(show(parseBoundUs('24/11/2026', true)), '24/11/2026 23:59:59.999999');
  assert.equal(show(parseBoundUs('24/11/2026 12', true)), '24/11/2026 12:59:59.999999');
  assert.equal(show(parseBoundUs('24/11/2026 12:30', true)), '24/11/2026 12:30:59.999999');
  assert.equal(show(parseBoundUs('24/11/2026 12:30:15', true)), '24/11/2026 12:30:15.999999');
  assert.equal(show(parseBoundUs('24/11/2026 12:30:15', false)), '24/11/2026 12:30:15.000000');
  assert.equal(parseBoundUs('24/11/26', false), null);
  assert.equal(parseBoundUs('31/02/2026', true), null);
});

test('parseBoundUs: whole Israel days across daylight saving changes', () => {
  // 2026: summer time starts Fri 27/03 (02:00 -> 03:00) and ends Sun 25/10 (02:00 -> 01:00)
  assert.equal(show(parseBoundUs('27/03/2026', true)), '27/03/2026 23:59:59.999999');
  assert.equal((parseBoundUs('27/03/2026', true)! + 1 - parseBoundUs('27/03/2026', false)!) / US_PER_HOUR, 23);
  assert.equal((parseBoundUs('25/10/2026', true)! + 1 - parseBoundUs('25/10/2026', false)!) / US_PER_HOUR, 25);
});

test('usToDay gives the Israel calendar day, not the UTC one', () => {
  assert.deepEqual(usToDay(parseBoundUs('24/11/2026 23:30', false)!), day(2026, 11, 24));
  // 00:30 in Israel is still the day before in UTC
  assert.deepEqual(usToDay(parseBoundUs('25/11/2026 00:30', false)!), day(2026, 11, 25));
});
