// Run with `npm test` (Node's built-in test runner; no test library needed).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { HistoryEntry } from '../api/types';
import {
  activeColumns, applyFilters, clearColumn, emptyFilters, filterOptions, filtersFromParams, filtersToParams,
  NO_PLAYLIST, playlistKey, UNNAMED_PLAYLIST, type HistoryFilters,
} from './historyFilters.js';

const MINUTE = 60_000;
const T0 = Date.UTC(2026, 10, 24, 10, 0, 0);

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
  entry('b', { recordName: 'Tracker session', categoryName: 'Tracker', mode: 'TCP_SERVER', target: '10.0.0.9:6000', startedBy: 'omer',
    startedAtMs: T0 + 10 * MINUTE, endedAtMs: T0 + 11 * MINUTE, messagesSent: 7, result: 'FAILED',
    playlist: { sessionId: 's1', index: 1, total: 2, name: 'Morning run' } }),
  entry('c', { recordName: 'Feed handshake', categoryName: 'Feed client', mode: 'TCP_CLIENT', target: 'listen :7000',
    startedAtMs: T0 + 20 * MINUTE, endedAtMs: T0 + 25 * MINUTE, messagesSent: 2, result: 'STOPPED', stoppedBy: 'dana',
    playlist: { sessionId: 's2', index: 1, total: 3 } }),
];

const ids = (filters: Partial<HistoryFilters>) => applyFilters(runs, { ...emptyFilters(), ...filters }).map((run) => run.id);

test('no filter shows every run in the original order', () => {
  assert.deepEqual(ids({}), ['a', 'b', 'c']);
});

test('Record: contains, case-insensitive', () => {
  assert.deepEqual(ids({ record: 'RADAR' }), ['a']);
  assert.deepEqual(ids({ record: '  sess ' }), ['b']);
});

test('Category: multi-select, values combine with OR', () => {
  assert.deepEqual(ids({ categories: ['Tracker'] }), ['b']);
  assert.deepEqual(ids({ categories: ['Tracker', 'Feed client'] }), ['b', 'c']);
});

test('Target: contains on host and port, case-insensitive', () => {
  assert.deepEqual(ids({ target: '10.0.0.9' }), ['b']);
  assert.deepEqual(ids({ target: ':5000' }), ['a']);
  assert.deepEqual(ids({ target: 'LISTEN' }), ['c']);
});

test('Started by: multi-select', () => {
  assert.deepEqual(ids({ users: ['omer'] }), ['b']);
  assert.deepEqual(ids({ users: ['dana', 'omer'] }), ['a', 'b', 'c']);
});

test('Start and End: inclusive ranges, either end may be open', () => {
  assert.deepEqual(ids({ startFrom: T0 + 10 * MINUTE }), ['b', 'c']);
  assert.deepEqual(ids({ startTo: T0 + 10 * MINUTE }), ['a', 'b']);
  assert.deepEqual(ids({ startFrom: T0 + 5 * MINUTE, startTo: T0 + 15 * MINUTE }), ['b']);
  assert.deepEqual(ids({ endFrom: T0 + 25 * MINUTE }), ['c']);
  assert.deepEqual(ids({ endFrom: T0 + 30 * MINUTE }), []);
});

test('Messages: min / max on messages sent', () => {
  assert.deepEqual(ids({ messagesMin: 7 }), ['a', 'b']);
  assert.deepEqual(ids({ messagesMax: 7 }), ['b', 'c']);
  assert.deepEqual(ids({ messagesMin: 3, messagesMax: 50 }), ['b']);
  assert.deepEqual(ids({ messagesMin: 50, messagesMax: 3 }), [], 'min > max matches nothing');
});

test('Result: multi-select of the result values', () => {
  assert.deepEqual(ids({ results: ['FAILED'] }), ['b']);
  assert.deepEqual(ids({ results: ['FAILED', 'STOPPED'] }), ['b', 'c']);
});

test('Playlist: by name, unnamed draft runs, or no playlist', () => {
  assert.equal(playlistKey(runs[0]), NO_PLAYLIST);
  assert.equal(playlistKey(runs[2]), UNNAMED_PLAYLIST);
  assert.deepEqual(ids({ playlists: ['name:Morning run'] }), ['b']);
  assert.deepEqual(ids({ playlists: [NO_PLAYLIST] }), ['a']);
  assert.deepEqual(ids({ playlists: [NO_PLAYLIST, UNNAMED_PLAYLIST] }), ['a', 'c']);
});

test('different columns combine with AND', () => {
  assert.deepEqual(ids({ users: ['dana'], results: ['STOPPED', 'COMPLETED'], target: 'listen' }), ['c']);
  assert.deepEqual(ids({ users: ['omer'], record: 'radar' }), []);
});

test('options list the values that occur, sorted, with the playlist extras', () => {
  const options = filterOptions(runs);
  assert.deepEqual(options.categories, ['Feed client', 'Radar UDP', 'Tracker']);
  assert.deepEqual(options.users, ['dana', 'omer']);
  assert.deepEqual(options.playlists, ['name:Morning run', UNNAMED_PLAYLIST, NO_PLAYLIST]);
});

test('active columns and clearing one of them', () => {
  const filters: HistoryFilters = { ...emptyFilters(), record: 'x', startTo: T0, results: ['FAILED'] };
  assert.deepEqual(activeColumns(filters), ['record', 'start', 'result']);
  assert.deepEqual(activeColumns(clearColumn(filters, 'start')), ['record', 'result']);
  assert.deepEqual(activeColumns(emptyFilters()), []);
});

test('URL: filters survive a round trip through the query string', () => {
  const filters: HistoryFilters = {
    ...emptyFilters(), record: 'radar', categories: ['Tracker', 'Feed client'], target: ':5000', users: ['omer'],
    startFrom: T0, endTo: T0 + MINUTE, messagesMin: 0, messagesMax: 50, results: ['FAILED', 'STOPPED'],
    playlists: [NO_PLAYLIST, 'name:Morning run'],
  };
  const query = filtersToParams(filters).toString();
  assert.deepEqual(filtersFromParams(new URLSearchParams(query)), filters);
});

test('URL: no filters means an empty query string', () => {
  assert.equal(filtersToParams(emptyFilters()).toString(), '');
});

test('URL: malformed or unknown values are ignored', () => {
  const filters = filtersFromParams(new URLSearchParams('msgMin=abc&startFrom=-5&result=BOGUS&result=FAILED&msgMax=12'));
  assert.equal(filters.messagesMin, null);
  assert.equal(filters.startFrom, null);
  assert.equal(filters.messagesMax, 12);
  assert.deepEqual(filters.results, ['FAILED']);
});
