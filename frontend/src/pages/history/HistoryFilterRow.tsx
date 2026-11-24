import { useCallback, useEffect, useRef, useState } from 'react';
import { useDebouncedCallback } from 'use-debounce';
import type { HistoryEntry } from '../../api/types';
import { AutocompleteInput } from '../../components/common/AutocompleteInput';
import { DateRangePicker } from '../../components/common/DateRangePicker';
import { Icon } from '../../components/common/Icon';
import { Popover } from '../../components/common/Popover';
import { useI18n } from '../../i18n';
import { toggleIn } from '../../util/list';
import {
  formatMessages, parseMessages, RESULTS, suggestions, type HistoryFilters, type HistoryResult, type PlaylistLabels, type TextField,
} from '../../util/historyFilters';

/** Typing settles for this long before the Messages filter is applied. */
const TYPING_DEBOUNCE_MS = 300;

interface Props {
  rows: HistoryEntry[];
  filters: HistoryFilters;
  labels: PlaylistLabels;
  /** Receives how to change the filters, so changes made in quick succession don't overwrite each other. */
  onChange: (change: (current: HistoryFilters) => HistoryFilters) => void;
  anyActive: boolean;
  onClearAll: () => void;
}

/**
 * The second header row of the History table: one compact filter under each column, and
 * "Clear all filters" at the end.
 */
export function HistoryFilterRow({ rows, filters, labels, onChange, anyActive, onClearAll }: Props) {
  const { t } = useI18n();
  const set = (patch: Partial<HistoryFilters>) => onChange((current) => ({ ...current, ...patch }));
  const text = (field: TextField, column: 'history.record' | 'history.category' | 'history.target' | 'history.startedBy' | 'history.playlist') => (
    <th>
      <TextFilter field={field} rows={rows} labels={labels} value={filters[field]} ariaLabel={t(column)}
        onCommit={(value) => set({ [field]: value })} />
    </th>
  );

  return (
    <tr className="filter-row">
      {text('record', 'history.record')}
      {text('category', 'history.category')}
      {text('target', 'history.target')}
      {text('user', 'history.startedBy')}
      <th>
        <DateRangePicker value={{ from: filters.startFrom, to: filters.startTo }} ariaLabel={t('history.start')}
          onChange={({ from, to }) => set({ startFrom: from, startTo: to })} />
      </th>
      <th>
        <DateRangePicker value={{ from: filters.endFrom, to: filters.endTo }} ariaLabel={t('history.end')}
          onChange={({ from, to }) => set({ endFrom: from, endTo: to })} />
      </th>
      <th>
        <MessagesFilter min={filters.messagesMin} max={filters.messagesMax}
          onCommit={(messagesMin, messagesMax) => set({ messagesMin, messagesMax })} />
      </th>
      <th>
        <ResultFilter chosen={filters.results}
          onToggle={(result) => onChange((current) => ({ ...current, results: toggleIn(current.results, result) }))}
          onClear={() => set({ results: [] })} />
      </th>
      {text('playlist', 'history.playlist')}
      <th className="clear-all-cell">
        <button className="btn ghost icon sm" disabled={!anyActive} onClick={onClearAll}
          aria-label={t('historyFilter.clearAll')} title={t('historyFilter.clearAll')}><Icon name="filter" size={13} /><Icon name="x" size={10} /></button>
      </th>
    </tr>
  );
}

/** "Contains" text with suggestions from the values that occur in this column. */
function TextFilter({ field, rows, labels, value, ariaLabel, onCommit }: {
  field: TextField; rows: HistoryEntry[]; labels: PlaylistLabels; value: string; ariaLabel: string; onCommit: (value: string) => void;
}) {
  const { t } = useI18n();
  const suggest = useCallback((typed: string) => suggestions(rows, field, typed, labels), [rows, field, labels]);
  return (
    <AutocompleteInput value={value} onCommit={onCommit} suggest={suggest} ariaLabel={ariaLabel}
      placeholder={t('historyFilter.contains')} clearLabel={t('historyFilter.remove')} />
  );
}

/** One input for messages sent: "100", ">100", "<50" or "100-500". Unreadable input is marked and not applied. */
function MessagesFilter({ min, max, onCommit }: {
  min: number | null; max: number | null; onCommit: (min: number | null, max: number | null) => void;
}) {
  const { t } = useI18n();
  const [text, setText] = useState(formatMessages({ min, max }));
  // follow outside changes (clear, URL), but not while the same range is just typed differently
  const lastRange = useRef({ min, max });
  useEffect(() => {
    if (lastRange.current.min !== min || lastRange.current.max !== max) setText(formatMessages({ min, max }));
    lastRange.current = { min, max };
  }, [min, max]);

  const commit = useDebouncedCallback((typed: string) => {
    const range = parseMessages(typed);
    if (range) {
      lastRange.current = range;
      onCommit(range.min, range.max);
    }
  }, TYPING_DEBOUNCE_MS);
  const invalid = parseMessages(text) === null;
  const active = min !== null || max !== null;

  return (
    <div className={`filter-field${active ? ' active' : ''}`}>
      <input className={`filter-input${invalid ? ' invalid' : ''}`} value={text} inputMode="numeric"
        placeholder={t('historyFilter.messagesHint')} aria-label={t('history.messages')} aria-invalid={invalid}
        title={invalid ? t('historyFilter.invalidMessages') : text || t('historyFilter.messagesHint')}
        onChange={(event) => { setText(event.target.value); commit(event.target.value); }} />
      {text && (
        <button className="filter-clear" aria-label={t('historyFilter.remove')} title={t('historyFilter.remove')}
          onClick={() => { commit.cancel(); setText(''); onCommit(null, null); }}><Icon name="x" size={11} /></button>
      )}
    </div>
  );
}

/** The same three Result options as before, in a compact field that opens them as a list. */
function ResultFilter({ chosen, onToggle, onClear }: {
  chosen: HistoryResult[]; onToggle: (result: HistoryResult) => void; onClear: () => void;
}) {
  const { t } = useI18n();
  const field = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const label = chosen.map((result) => t(`status.${result}`)).join(', ');
  return (
    <div className={`filter-field${chosen.length ? ' active' : ''}`}>
      <button ref={field} className="filter-input filter-button" aria-label={t('history.result')} aria-expanded={open}
        title={label || undefined} onClick={() => setOpen(!open)}>
        <span className={`ellipsis${label ? '' : ' placeholder'}`}>{label || t('historyFilter.anyResult')}</span>
        <Icon name="chevronDown" size={12} />
      </button>
      {chosen.length > 0 && (
        <button className="filter-clear" aria-label={t('historyFilter.remove')} title={t('historyFilter.remove')}
          onClick={onClear}><Icon name="x" size={11} /></button>
      )}
      {open && (
        <Popover anchor={field.current} onClose={() => setOpen(false)} className="option-list">
          {RESULTS.map((result) => (
            <label key={result} className="check">
              <input type="checkbox" checked={chosen.includes(result)} onChange={() => onToggle(result)} />
              {t(`status.${result}`)}
            </label>
          ))}
        </Popover>
      )}
    </div>
  );
}
