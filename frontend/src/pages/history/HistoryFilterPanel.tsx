import { useEffect, useState } from 'react';
import { useDebouncedCallback } from 'use-debounce';
import { useI18n } from '../../i18n';
import { toggleIn } from '../../util/list';
import {
  NO_PLAYLIST, RESULTS, UNNAMED_PLAYLIST, playlistName, type HistoryFilters, type HistoryResult,
} from '../../util/historyFilters';
import { formatMs, parseMs } from '../../util/time';

/** Typing settles for this long before a text filter is applied (and written to the URL). */
const TYPING_DEBOUNCE_MS = 300;
/** A typed end time means "through the end of that second". */
const END_OF_SECOND_MS = 999;

interface Props {
  filters: HistoryFilters;
  options: { categories: string[]; users: string[]; playlists: string[] };
  /** Receives how to change the filters, so changes made in quick succession don't overwrite each other. */
  onChange: (change: (current: HistoryFilters) => HistoryFilters) => void;
}

/** The multi-select filters, by their field in HistoryFilters. */
type ChoiceField = 'categories' | 'users' | 'results' | 'playlists';

/** The filter inputs above the History table, one per column. */
export function HistoryFilterPanel({ filters, options, onChange }: Props) {
  const { t } = useI18n();
  const set = (patch: Partial<HistoryFilters>) => onChange((current) => ({ ...current, ...patch }));
  const toggle = (field: ChoiceField) => (value: string) =>
    onChange((current) => ({ ...current, [field]: toggleIn(current[field] as string[], value) }));
  const playlistLabel = usePlaylistLabel();

  return (
    <div className="card flat history-filters">
      <div className="history-filter-grid">
        <TextFilter label={t('history.record')} value={filters.record} onCommit={(record) => set({ record })} />
        <TextFilter label={t('history.target')} value={filters.target} onCommit={(target) => set({ target })} />
        <RangeFilter label={t('history.messages')} kind="number" from={filters.messagesMin} to={filters.messagesMax}
          onCommit={(messagesMin, messagesMax) => set({ messagesMin, messagesMax })} />
        <RangeFilter label={t('history.start')} kind="time" from={filters.startFrom} to={filters.startTo}
          onCommit={(startFrom, startTo) => set({ startFrom, startTo })} />
        <RangeFilter label={t('history.end')} kind="time" from={filters.endFrom} to={filters.endTo}
          onCommit={(endFrom, endTo) => set({ endFrom, endTo })} />
      </div>
      <ChoiceFilter label={t('history.category')} values={options.categories} chosen={filters.categories}
        onToggle={toggle('categories')} />
      <ChoiceFilter label={t('history.startedBy')} values={options.users} chosen={filters.users}
        onToggle={toggle('users')} />
      <ChoiceFilter label={t('history.result')} values={[...RESULTS]} chosen={filters.results}
        labelOf={(result) => t(`status.${result as HistoryResult}`)} onToggle={toggle('results')} />
      <ChoiceFilter label={t('history.playlist')} values={options.playlists} chosen={filters.playlists}
        labelOf={playlistLabel} onToggle={toggle('playlists')} />
    </div>
  );
}

/** How a playlist filter value reads: the saved playlist's name, "Unnamed playlist" or "No playlist". */
export function usePlaylistLabel(): (key: string) => string {
  const { t } = useI18n();
  return (key) => key === NO_PLAYLIST ? t('historyFilter.noPlaylist')
    : key === UNNAMED_PLAYLIST ? t('historyFilter.unnamedPlaylist') : playlistName(key) ?? key;
}

/** "Contains" text: shown at once, applied after typing pauses. Follows outside changes (clear, URL). */
function TextFilter({ label, value, onCommit }: { label: string; value: string; onCommit: (value: string) => void }) {
  const { t } = useI18n();
  const [text, setText] = useState(value);
  const commit = useDebouncedCallback(onCommit, TYPING_DEBOUNCE_MS);
  useEffect(() => setText(value), [value]);

  return (
    <div className="field">
      <label>{label}</label>
      <input className="input" value={text} placeholder={t('historyFilter.contains')}
        onChange={(e) => { setText(e.target.value); commit(e.target.value); }} />
    </div>
  );
}

/**
 * From / to: whole numbers, or times typed as DD/MM/YYYY HH:MM:SS (Israel time, like the table).
 * Either end may be empty. A value that can't be read is marked and not applied.
 */
function RangeFilter({ label, kind, from, to, onCommit }: {
  label: string; kind: 'number' | 'time'; from: number | null; to: number | null;
  onCommit: (from: number | null, to: number | null) => void;
}) {
  const { t } = useI18n();
  const show = (value: number | null) => (value === null ? '' : kind === 'time' ? formatMs(value) : String(value));
  const read = (text: string, isEnd: boolean): number | null | undefined => {
    if (!text.trim()) return null;
    if (kind === 'number') return /^\d+$/.test(text.trim()) ? Number(text) : undefined;
    const ms = parseMs(text);
    return ms === null ? undefined : ms + (isEnd ? END_OF_SECOND_MS : 0); // undefined = can't be read
  };

  const [fromText, setFromText] = useState(show(from));
  const [toText, setToText] = useState(show(to));
  useEffect(() => setFromText(show(from)), [from]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => setToText(show(to)), [to]); // eslint-disable-line react-hooks/exhaustive-deps

  const fromValue = read(fromText, false);
  const toValue = read(toText, true);
  const reversed = fromValue != null && toValue != null && fromValue > toValue;
  const commit = useDebouncedCallback((nextFrom: string, nextTo: string) => {
    const a = read(nextFrom, false);
    const b = read(nextTo, true);
    if (a !== undefined && b !== undefined) onCommit(a, b);
  }, TYPING_DEBOUNCE_MS);

  const placeholder = kind === 'time' ? t('historyFilter.timeFormat') : '';
  const inputClass = (value: number | null | undefined) => `input${kind === 'time' ? ' time' : ''}${value === undefined || reversed ? ' invalid' : ''}`;
  return (
    <div className="field">
      <label>{label}</label>
      <div className="range-inputs">
        <input className={inputClass(fromValue)} value={fromText} inputMode={kind === 'number' ? 'numeric' : undefined}
          placeholder={`${kind === 'time' ? t('historyFilter.from') : t('historyFilter.min')} ${placeholder}`.trim()}
          onChange={(e) => { setFromText(e.target.value); commit(e.target.value, toText); }} />
        <input className={inputClass(toValue)} value={toText} inputMode={kind === 'number' ? 'numeric' : undefined}
          placeholder={`${kind === 'time' ? t('historyFilter.to') : t('historyFilter.max')} ${placeholder}`.trim()}
          onChange={(e) => { setToText(e.target.value); commit(fromText, e.target.value); }} />
      </div>
      {reversed && <span className="error-text">{t(kind === 'time' ? 'historyFilter.fromAfterTo' : 'historyFilter.minAboveMax')}</span>}
    </div>
  );
}

/** Multi-select as toggle chips (like the tag filter on the Player page); none chosen = no filter. */
function ChoiceFilter({ label, values, chosen, labelOf = (value) => value, onToggle }: {
  label: string; values: string[]; chosen: string[]; labelOf?: (value: string) => string; onToggle: (value: string) => void;
}) {
  if (values.length === 0) return null;
  return (
    <div className="tag-filter">
      <span className="label" style={{ alignSelf: 'center', minWidth: 110 }}>{label}</span>
      {values.map((value) => {
        const on = chosen.includes(value);
        return (
          <button key={value} className={`chip selectable${on ? ' on' : ''}`} aria-pressed={on}
            onClick={() => onToggle(value)}><bdi>{labelOf(value)}</bdi></button>
        );
      })}
    </div>
  );
}
