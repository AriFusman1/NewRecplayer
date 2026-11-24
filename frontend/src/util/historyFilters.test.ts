// Run with `npm test` (Node's built-in test runner; no test library needed).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { HistoryEntry } from '../api/types';
import {
  activeColumns, applyFilters, clearColumn, emptyFilters, filtersFromParams, filtersToParams, formatMessages,
  parseMessages, playlistText, suggestions, type HistoryFilters,
} from './historyFilters.js';

const MINUTE = 60_000;
const T0 = Date.UTC(2026, 10, 24, 10, 0, 0);
const LABELS = { none: 'No playlist', unnamed: 'Unnamed playlist' };

function entry(id: string, overrides: Partial<HistoryEntry> = {}): HistoryEntry {
  return {
    id, recordId: 'r-' + id, recordName: 'Radar ' + id, categoryName: 'Radar UDP', mode: 'UDP_SERVER',
    target: '10.0.0.5:5000', startedBy: 'dana', startedAtMs: T0, endedAtMs: T0 + MINUTE,
    messagesSent: 100, totalMessages: 100, bytesSent: 1000, result: 'COMPLETED', stoppedBy: null,
    failureCode: null, failureParams: null, playlist: null, ...overrides,
  };
}

const runs: HistoryEntry[] = [
  entry('a'),
  entry('b', { recordName: 'Tracker session', categoryName: 'Tracker', mode: 'TCP_SERVER', target: '10.0.0.9:6000', startedBy: 'yahali',
    startedAtMs: T0 + 10 * MINUTE, endedAtMs: T0 + 11 * MINUTE, messagesSent: 7, result: 'FAILED',
    playlist: { sessionId: 's1', index: 1, total: 2, name: 'Morning run' } }),
  entry('c', { recordName: 'Feed handshake', categoryName: 'Feed client', mode: 'TCP_CLIENT', target: 'listen :7000',
    startedBy: 'yaniv', startedAtMs: T0 + 20 * MINUTE, endedAtMs: T0 + 25 * MINUTE, messagesSent: 2, result: 'STOPPED',
    stoppedBy: 'dana', playlist: { sessionId: 's2', index: 1, total: 3 } }),
];

const ids = (filters: Partial<HistoryFilters>) => applyFilters(runs, { ...emptyFilters(), ...filters }, LABELS).map((run) => run.id);

test('no filter shows every run in the original order', () => {
  assert.deepEqual(ids({}), ['a', 'b', 'c']);
});

test('text filters: contains, case-insensitive, on every text column', () => {
  assert.deepEqual(ids({ record: 'RADAR' }), ['a']);
  assert.deepEqual(ids({ category: 'track' }), ['b']);
  assert.deepEqual(ids({ category: 'radar u' }), ['a']);
  assert.deepEqual(ids({ target: ':5000' }), ['a']);
  assert.deepEqual(ids({ target: 'LISTEN' }), ['c']);
  assert.deepEqual(ids({ user: 'yah' }), ['b']);
  assert.deepEqual(ids({ user: 'ya' }), ['b', 'c']);
});

test('Playlist matches the column as shown: name, "Unnamed playlist" or "No playlist"', () => {
  assert.equal(playlistText(runs[0], LABELS), 'No playlist');
  assert.equal(playlistText(runs[2], LABELS), 'Unnamed playlist');
  assert.deepEqual(ids({ playlist: 'morning' }), ['b']);
  assert.deepEqual(ids({ playlist: 'no pl' }), ['a']);
  assert.deepEqual(ids({ playlist: 'playlist' }), ['a', 'c']);
});

test('Start and End: inclusive ranges, either end may be open', () => {
  assert.deepEqual(ids({ startFrom: T0 + 10 * MINUTE }), ['b', 'c']);
  assert.deepEqual(ids({ startTo: T0 + 10 * MINUTE }), ['a', 'b']);
  assert.deepEqual(ids({ startFrom: T0 + 5 * MINUTE, startTo: T0 + 15 * MINUTE }), ['b']);
  assert.deepEqual(ids({ endFrom: T0 + 25 * MINUTE }), ['c']);
});

test('Result: the same three options, any of them matches', () => {
  assert.deepEqual(ids({ results: ['FAILED'] }), ['b']);
  assert.deepEqual(ids({ results: ['FAILED', 'STOPPED'] }), ['b', 'c']);
});

test('different columns combine with AND', () => {
  assert.deepEqual(ids({ user: 'ya', results: ['STOPPED'], target: 'listen' }), ['c']);
  assert.deepEqual(ids({ user: 'yahali', record: 'radar' }), []);
});

test('suggestions: distinct matching values, "starts with" first, at most 10', () => {
  assert.deepEqual(suggestions(runs, 'user', 'yah', LABELS), ['yahali']);
  assert.deepEqual(suggestions(runs, 'user', 'a', LABELS), ['dana', 'yahali', 'yaniv']);
  assert.deepEqual(suggestions(runs, 'category', 'cli', LABELS), ['Feed client']);
  assert.deepEqual(suggestions(runs, 'playlist', '', LABELS), ['Morning run', 'No playlist', 'Unnamed playlist']);
  const many = Array.from({ length: 15 }, (_, index) => entry(String(index), { startedBy: `user${String(index).padStart(2, '0')}` }));
  assert.equal(suggestions(many, 'user', 'user', LABELS).length, 10);
});

test('Messages syntax: exact, bounds and ranges', () => {
  assert.deepEqual(parseMessages(''), { min: null, max: null });
  assert.deepEqual(parseMessages('100'), { min: 100, max: 100 });
  assert.deepEqual(parseMessages('>100'), { min: 101, max: null });
  assert.deepEqual(parseMessages('>=100'), { min: 100, max: null });
  assert.deepEqual(parseMessages('<50'), { min: null, max: 49 });
  assert.deepEqual(parseMessages('<= 50'), { min: null, max: 50 });
  assert.deepEqual(parseMessages(' 100 - 500 '), { min: 100, max: 500 });
});

test('Messages syntax: invalid input is rejected', () => {
  for (const bad of ['abc', '500-100', '<0', '1.5', '-5', '>', '10-', '>>3']) {
    assert.equal(parseMessages(bad), null, bad);
  }
});

test('Messages syntax: a range is written back the way it is typed', () => {
  for (const text of ['100', '>100', '<50', '100-500', '']) {
    assert.equal(formatMessages(parseMessages(text)!), text);
  }
  const below50 = parseMessages('<50')!;
  assert.deepEqual(ids({ messagesMin: below50.min, messagesMax: below50.max }), ['b', 'c']);
  const range = parseMessages('5-50')!;
  assert.deepEqual(ids({ messagesMin: range.min, messagesMax: range.max }), ['b']);
});

test('active columns and clearing one of them', () => {
  const filters: HistoryFilters = { ...emptyFilters(), record: 'x', startTo: T0, results: ['FAILED'], user: 'ya' };
  assert.deepEqual(activeColumns(filters), ['record', 'startedBy', 'start', 'result']);
  assert.deepEqual(activeColumns(clearColumn(filters, 'start')), ['record', 'startedBy', 'result']);
  assert.deepEqual(activeColumns(emptyFilters()), []);
});

test('URL: filters survive a round trip through the query string', () => {
  const filters: HistoryFilters = {
    ...emptyFilters(), record: 'radar', category: 'Tracker', target: ':5000', user: 'yah', playlist: 'no playlist',
    startFrom: T0, endTo: T0 + MINUTE, messagesMin: 0, messagesMax: 50, results: ['FAILED', 'STOPPED'],
  };
  assert.deepEqual(filtersFromParams(new URLSearchParams(filtersToParams(filters).toString())), filters);
  assert.equal(filtersToParams(emptyFilters()).toString(), '', 'no filters, clean URL');
});

test('URL: malformed or unknown values are ignored', () => {
  const filters = filtersFromParams(new URLSearchParams('msgMin=abc&startFrom=-5&result=BOGUS&result=FAILED&msgMax=12'));
  assert.equal(filters.messagesMin, null);
  assert.equal(filters.startFrom, null);
  assert.equal(filters.messagesMax, 12);
  assert.deepEqual(filters.results, ['FAILED']);
});
