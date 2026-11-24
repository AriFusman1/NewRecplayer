import type { HistoryEntry } from '../api/types';

// Filtering for the History screen. History holds at most `history.size` (20) runs and the whole
// list is already in the browser, so filtering happens here, in plain functions (no React), which
// keeps the page simple and lets the logic be tested on its own.
//
// Rules: different columns combine with AND; several values in one multi-select combine with OR.
// The state lives in the URL query string, so a filtered view survives a refresh and can be shared.

export const RESULTS = ['COMPLETED', 'STOPPED', 'FAILED'] as const;
export type HistoryResult = (typeof RESULTS)[number];

/** Playlist filter values: a saved playlist's name, an unnamed (draft) playlist run, or no playlist. */
export const NO_PLAYLIST = 'none';
export const UNNAMED_PLAYLIST = 'unnamed';
const NAMED_PREFIX = 'name:';

export interface HistoryFilters {
  /** "contains", case-insensitive */
  record: string;
  /** category names; any of them matches */
  categories: string[];
  /** "contains" on the target text ("10.0.0.5:5000", "listen :7000"), case-insensitive */
  target: string;
  /** nicknames; any of them matches */
  users: string[];
  /** epoch ms, both ends inclusive; null = open */
  startFrom: number | null;
  startTo: number | null;
  endFrom: number | null;
  endTo: number | null;
  /** messages sent, both ends inclusive; null = open */
  messagesMin: number | null;
  messagesMax: number | null;
  results: HistoryResult[];
  /** playlistKey() values */
  playlists: string[];
}

/** The columns of the History table; each can have one active filter. */
export type FilterColumn = 'record' | 'category' | 'target' | 'startedBy' | 'start' | 'end' | 'messages' | 'result' | 'playlist';

export function emptyFilters(): HistoryFilters {
  return {
    record: '', categories: [], target: '', users: [],
    startFrom: null, startTo: null, endFrom: null, endTo: null,
    messagesMin: null, messagesMax: null, results: [], playlists: [],
  };
}

/** Which playlist a run belongs to, as a filter value. */
export function playlistKey(entry: HistoryEntry): string {
  if (!entry.playlist) return NO_PLAYLIST;
  return entry.playlist.name ? NAMED_PREFIX + entry.playlist.name : UNNAMED_PLAYLIST;
}

/** The saved playlist's name inside a playlistKey(), or null for "none" / "unnamed". */
export function playlistName(key: string): string | null {
  return key.startsWith(NAMED_PREFIX) ? key.slice(NAMED_PREFIX.length) : null;
}

// ------------------------------------------------------------------ matching

const contains = (text: string, part: string) => text.toLowerCase().includes(part.trim().toLowerCase());
const inRange = (value: number, min: number | null, max: number | null) =>
  (min === null || value >= min) && (max === null || value <= max);
const anyOf = <T>(chosen: T[], value: T) => chosen.length === 0 || chosen.includes(value);

export function matches(entry: HistoryEntry, filters: HistoryFilters): boolean {
  return contains(entry.recordName, filters.record)
    && anyOf(filters.categories, entry.categoryName)
    && contains(entry.target, filters.target)
    && anyOf(filters.users, entry.startedBy)
    && inRange(entry.startedAtMs, filters.startFrom, filters.startTo)
    && inRange(entry.endedAtMs, filters.endFrom, filters.endTo)
    && inRange(entry.messagesSent, filters.messagesMin, filters.messagesMax)
    && anyOf<string>(filters.results, entry.result)
    && anyOf(filters.playlists, playlistKey(entry));
}

export function applyFilters(entries: HistoryEntry[], filters: HistoryFilters): HistoryEntry[] {
  return entries.filter((entry) => matches(entry, filters));
}

// ------------------------------------------------------------------ choices for the multi-selects

const distinct = (values: string[]) => [...new Set(values)].sort((left, right) => left.localeCompare(right));

/** The values that actually occur, for the Category, Started by and Playlist pickers. */
export function filterOptions(entries: HistoryEntry[]) {
  const named = distinct(entries.flatMap((entry) => (entry.playlist?.name ? [NAMED_PREFIX + entry.playlist.name] : [])));
  const hasUnnamed = entries.some((entry) => entry.playlist && !entry.playlist.name);
  return {
    categories: distinct(entries.map((entry) => entry.categoryName)),
    users: distinct(entries.map((entry) => entry.startedBy)),
    playlists: [...named, ...(hasUnnamed ? [UNNAMED_PLAYLIST] : []), NO_PLAYLIST],
  };
}

// ------------------------------------------------------------------ active filters (chips, header icons)

export function activeColumns(filters: HistoryFilters): FilterColumn[] {
  const active: FilterColumn[] = [];
  if (filters.record.trim()) active.push('record');
  if (filters.categories.length) active.push('category');
  if (filters.target.trim()) active.push('target');
  if (filters.users.length) active.push('startedBy');
  if (filters.startFrom !== null || filters.startTo !== null) active.push('start');
  if (filters.endFrom !== null || filters.endTo !== null) active.push('end');
  if (filters.messagesMin !== null || filters.messagesMax !== null) active.push('messages');
  if (filters.results.length) active.push('result');
  if (filters.playlists.length) active.push('playlist');
  return active;
}

/** The same filters with one column's filter removed. */
export function clearColumn(filters: HistoryFilters, column: FilterColumn): HistoryFilters {
  const empty = emptyFilters();
  switch (column) {
    case 'record': return { ...filters, record: empty.record };
    case 'category': return { ...filters, categories: empty.categories };
    case 'target': return { ...filters, target: empty.target };
    case 'startedBy': return { ...filters, users: empty.users };
    case 'start': return { ...filters, startFrom: null, startTo: null };
    case 'end': return { ...filters, endFrom: null, endTo: null };
    case 'messages': return { ...filters, messagesMin: null, messagesMax: null };
    case 'result': return { ...filters, results: empty.results };
    case 'playlist': return { ...filters, playlists: empty.playlists };
  }
}

// ------------------------------------------------------------------ URL query string

/** Query-string names; multi-selects repeat their name (?result=FAILED&result=STOPPED). */
const PARAM = {
  record: 'record', categories: 'category', target: 'target', users: 'user',
  startFrom: 'startFrom', startTo: 'startTo', endFrom: 'endFrom', endTo: 'endTo',
  messagesMin: 'msgMin', messagesMax: 'msgMax', results: 'result', playlists: 'playlist',
} as const;

/** A whole, non-negative number from the URL, or null (missing or malformed values are ignored). */
function numberParam(params: URLSearchParams, name: string): number | null {
  const raw = params.get(name);
  if (raw === null || !/^\d+$/.test(raw.trim())) return null;
  return Number(raw);
}

/** Reads the filters from the URL. Unknown or malformed values are ignored rather than breaking the page. */
export function filtersFromParams(params: URLSearchParams): HistoryFilters {
  return {
    record: params.get(PARAM.record) ?? '',
    categories: params.getAll(PARAM.categories),
    target: params.get(PARAM.target) ?? '',
    users: params.getAll(PARAM.users),
    startFrom: numberParam(params, PARAM.startFrom),
    startTo: numberParam(params, PARAM.startTo),
    endFrom: numberParam(params, PARAM.endFrom),
    endTo: numberParam(params, PARAM.endTo),
    messagesMin: numberParam(params, PARAM.messagesMin),
    messagesMax: numberParam(params, PARAM.messagesMax),
    results: params.getAll(PARAM.results).filter((value): value is HistoryResult => (RESULTS as readonly string[]).includes(value)),
    playlists: params.getAll(PARAM.playlists),
  };
}

/** Writes only the filters that are set, so an unfiltered History has a clean URL. */
export function filtersToParams(filters: HistoryFilters): URLSearchParams {
  const params = new URLSearchParams();
  const text = (name: string, value: string) => { if (value.trim()) params.set(name, value); };
  const number = (name: string, value: number | null) => { if (value !== null) params.set(name, String(value)); };
  const list = (name: string, values: string[]) => values.forEach((value) => params.append(name, value));

  text(PARAM.record, filters.record);
  list(PARAM.categories, filters.categories);
  text(PARAM.target, filters.target);
  list(PARAM.users, filters.users);
  number(PARAM.startFrom, filters.startFrom);
  number(PARAM.startTo, filters.startTo);
  number(PARAM.endFrom, filters.endFrom);
  number(PARAM.endTo, filters.endTo);
  number(PARAM.messagesMin, filters.messagesMin);
  number(PARAM.messagesMax, filters.messagesMax);
  list(PARAM.results, filters.results);
  list(PARAM.playlists, filters.playlists);
  return params;
}
