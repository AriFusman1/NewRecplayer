import type { HistoryEntry } from '../api/types';

// Filtering for the History screen. History holds at most `history.size` (20) runs and the whole
// list is already in the browser, so filtering happens here, in plain functions (no React), which
// keeps the page simple and lets the logic be tested on its own.
//
// Rules: different columns combine with AND. Text filters match "contains", case-insensitive.
// The state lives in the URL query string, so a filtered view survives a refresh and can be shared.

export const RESULTS = ['COMPLETED', 'STOPPED', 'FAILED'] as const;
export type HistoryResult = (typeof RESULTS)[number];

/** Suggestions shown under a text filter. */
const MAX_SUGGESTIONS = 10;

export interface HistoryFilters {
  /** "contains", case-insensitive, like every text filter */
  record: string;
  category: string;
  /** matched against the target as shown ("10.0.0.5:5000", "listen :7000") */
  target: string;
  user: string;
  /** matched against the playlist as shown: its name, "Unnamed playlist" or "No playlist" */
  playlist: string;
  /** epoch ms, both ends inclusive; null = open */
  startFrom: number | null;
  startTo: number | null;
  endFrom: number | null;
  endTo: number | null;
  /** messages sent, both ends inclusive; null = open */
  messagesMin: number | null;
  messagesMax: number | null;
  /** any of them matches; none = no filter */
  results: HistoryResult[];
}

/** The columns of the History table; each can have one active filter. */
export type FilterColumn = 'record' | 'category' | 'target' | 'startedBy' | 'start' | 'end' | 'messages' | 'result' | 'playlist';

/** The text filters, by the field that holds them. */
export type TextField = 'record' | 'category' | 'target' | 'user' | 'playlist';

/** How the Playlist column reads for runs without a saved playlist name (translated by the caller). */
export interface PlaylistLabels {
  none: string;
  unnamed: string;
}

export function emptyFilters(): HistoryFilters {
  return {
    record: '', category: '', target: '', user: '', playlist: '',
    startFrom: null, startTo: null, endFrom: null, endTo: null,
    messagesMin: null, messagesMax: null, results: [],
  };
}

/** The Playlist column as shown: the saved playlist's name, "Unnamed playlist" or "No playlist". */
export function playlistText(entry: HistoryEntry, labels: PlaylistLabels): string {
  if (!entry.playlist) return labels.none;
  return entry.playlist.name || labels.unnamed;
}

/** The text a text filter is matched against, for one run. */
export function textOf(entry: HistoryEntry, field: TextField, labels: PlaylistLabels): string {
  switch (field) {
    case 'record': return entry.recordName;
    case 'category': return entry.categoryName;
    case 'target': return entry.target;
    case 'user': return entry.startedBy;
    case 'playlist': return playlistText(entry, labels);
  }
}

// ------------------------------------------------------------------ matching

const TEXT_FIELDS: TextField[] = ['record', 'category', 'target', 'user', 'playlist'];
const contains = (text: string, part: string) => text.toLowerCase().includes(part.trim().toLowerCase());
const inRange = (value: number, min: number | null, max: number | null) =>
  (min === null || value >= min) && (max === null || value <= max);

export function matches(entry: HistoryEntry, filters: HistoryFilters, labels: PlaylistLabels): boolean {
  return TEXT_FIELDS.every((field) => contains(textOf(entry, field, labels), filters[field]))
    && inRange(entry.startedAtMs, filters.startFrom, filters.startTo)
    && inRange(entry.endedAtMs, filters.endFrom, filters.endTo)
    && inRange(entry.messagesSent, filters.messagesMin, filters.messagesMax)
    && (filters.results.length === 0 || (filters.results as string[]).includes(entry.result));
}

export function applyFilters(entries: HistoryEntry[], filters: HistoryFilters, labels: PlaylistLabels): HistoryEntry[] {
  return entries.filter((entry) => matches(entry, filters, labels));
}

// ------------------------------------------------------------------ autocomplete

/**
 * Up to 10 distinct values of a column that contain what was typed (case-insensitive), those that
 * start with it first, then alphabetically. Nothing typed: the first 10 values.
 */
export function suggestions(entries: HistoryEntry[], field: TextField, typed: string, labels: PlaylistLabels): string[] {
  const part = typed.trim().toLowerCase();
  const values = [...new Set(entries.map((entry) => textOf(entry, field, labels)))]
    .filter((value) => value.toLowerCase().includes(part));
  const startsWith = (value: string) => (value.toLowerCase().startsWith(part) ? 0 : 1);
  return values
    .sort((left, right) => startsWith(left) - startsWith(right) || left.localeCompare(right))
    .slice(0, MAX_SUGGESTIONS);
}

// ------------------------------------------------------------------ Messages: "100", ">100", "<50", "100-500"

export interface NumberRange {
  min: number | null;
  max: number | null;
}

/**
 * Reads the Messages filter. Accepts an exact number ("100"), a bound (">100", ">=100", "<50",
 * "<=50") or a range ("100-500"). Empty = no filter.
 *
 * @return the range, or null if the text can't be read (e.g. "abc", "500-100")
 */
export function parseMessages(text: string): NumberRange | null {
  const value = text.replace(/\s+/g, '');
  if (value === '') return { min: null, max: null };
  let match: RegExpMatchArray | null;
  if ((match = value.match(/^(\d+)$/))) return { min: Number(match[1]), max: Number(match[1]) };
  if ((match = value.match(/^>(=?)(\d+)$/))) return { min: Number(match[2]) + (match[1] ? 0 : 1), max: null };
  if ((match = value.match(/^<(=?)(\d+)$/))) {
    const max = Number(match[2]) - (match[1] ? 0 : 1);
    return max < 0 ? null : { min: null, max };
  }
  if ((match = value.match(/^(\d+)-(\d+)$/))) {
    const [min, max] = [Number(match[1]), Number(match[2])];
    return min <= max ? { min, max } : null;
  }
  return null;
}

/** Writes a range back in the same syntax ("100", ">100", "<50", "100-500"); empty when open. */
export function formatMessages({ min, max }: NumberRange): string {
  if (min !== null && max !== null) return min === max ? String(min) : `${min}-${max}`;
  if (min !== null) return `>${min - 1}`;
  if (max !== null) return `<${max + 1}`;
  return '';
}

// ------------------------------------------------------------------ active filters (header icons, clear buttons)

export function activeColumns(filters: HistoryFilters): FilterColumn[] {
  const active: FilterColumn[] = [];
  if (filters.record.trim()) active.push('record');
  if (filters.category.trim()) active.push('category');
  if (filters.target.trim()) active.push('target');
  if (filters.user.trim()) active.push('startedBy');
  if (filters.startFrom !== null || filters.startTo !== null) active.push('start');
  if (filters.endFrom !== null || filters.endTo !== null) active.push('end');
  if (filters.messagesMin !== null || filters.messagesMax !== null) active.push('messages');
  if (filters.results.length) active.push('result');
  if (filters.playlist.trim()) active.push('playlist');
  return active;
}

/** The same filters with one column's filter removed. */
export function clearColumn(filters: HistoryFilters, column: FilterColumn): HistoryFilters {
  switch (column) {
    case 'record': return { ...filters, record: '' };
    case 'category': return { ...filters, category: '' };
    case 'target': return { ...filters, target: '' };
    case 'startedBy': return { ...filters, user: '' };
    case 'start': return { ...filters, startFrom: null, startTo: null };
    case 'end': return { ...filters, endFrom: null, endTo: null };
    case 'messages': return { ...filters, messagesMin: null, messagesMax: null };
    case 'result': return { ...filters, results: [] };
    case 'playlist': return { ...filters, playlist: '' };
  }
}

// ------------------------------------------------------------------ URL query string

/** Query-string names (unchanged from the first version of these filters, so saved links still work). */
const PARAM = {
  record: 'record', category: 'category', target: 'target', user: 'user', playlist: 'playlist',
  startFrom: 'startFrom', startTo: 'startTo', endFrom: 'endFrom', endTo: 'endTo',
  messagesMin: 'msgMin', messagesMax: 'msgMax', results: 'result',
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
    category: params.get(PARAM.category) ?? '',
    target: params.get(PARAM.target) ?? '',
    user: params.get(PARAM.user) ?? '',
    playlist: params.get(PARAM.playlist) ?? '',
    startFrom: numberParam(params, PARAM.startFrom),
    startTo: numberParam(params, PARAM.startTo),
    endFrom: numberParam(params, PARAM.endFrom),
    endTo: numberParam(params, PARAM.endTo),
    messagesMin: numberParam(params, PARAM.messagesMin),
    messagesMax: numberParam(params, PARAM.messagesMax),
    results: params.getAll(PARAM.results).filter((value): value is HistoryResult => (RESULTS as readonly string[]).includes(value)),
  };
}

/** Writes only the filters that are set, so an unfiltered History has a clean URL. */
export function filtersToParams(filters: HistoryFilters): URLSearchParams {
  const params = new URLSearchParams();
  const text = (name: string, value: string) => { if (value.trim()) params.set(name, value); };
  const number = (name: string, value: number | null) => { if (value !== null) params.set(name, String(value)); };

  text(PARAM.record, filters.record);
  text(PARAM.category, filters.category);
  text(PARAM.target, filters.target);
  text(PARAM.user, filters.user);
  text(PARAM.playlist, filters.playlist);
  number(PARAM.startFrom, filters.startFrom);
  number(PARAM.startTo, filters.startTo);
  number(PARAM.endFrom, filters.endFrom);
  number(PARAM.endTo, filters.endTo);
  number(PARAM.messagesMin, filters.messagesMin);
  number(PARAM.messagesMax, filters.messagesMax);
  filters.results.forEach((result) => params.append(PARAM.results, result));
  return params;
}
