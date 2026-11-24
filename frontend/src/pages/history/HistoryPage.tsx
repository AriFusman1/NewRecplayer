import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { HistoryEntry } from '../../api/types';
import { ModeBadge } from '../../components/common/Badges';
import { Icon } from '../../components/common/Icon';
import { useI18n } from '../../i18n';
import { useFeedback } from '../../state/feedback';
import { useLive } from '../../state/live';
import {
  activeColumns, applyFilters, clearColumn, emptyFilters, filterOptions, filtersFromParams, filtersToParams,
  type FilterColumn, type HistoryFilters,
} from '../../util/historyFilters';
import { formatBytes, formatMs, formatNumber } from '../../util/time';
import { HistoryFilterPanel, usePlaylistLabel } from './HistoryFilterPanel';

/** The table's columns, in order, with their header text. */
const COLUMNS: { key: FilterColumn; label: 'history.record' | 'history.category' | 'history.target' | 'history.startedBy'
  | 'history.start' | 'history.end' | 'history.messages' | 'history.result' | 'history.playlist' }[] = [
  { key: 'record', label: 'history.record' }, { key: 'category', label: 'history.category' },
  { key: 'target', label: 'history.target' }, { key: 'startedBy', label: 'history.startedBy' },
  { key: 'start', label: 'history.start' }, { key: 'end', label: 'history.end' },
  { key: 'messages', label: 'history.messages' }, { key: 'result', label: 'history.result' },
  { key: 'playlist', label: 'history.playlist' },
];

/**
 * The last 20 runs (stored in S3), with a filter per column. The list is small and already in the
 * browser, so filtering happens here; the filters live in the URL query string.
 */
export function HistoryPage() {
  const { t } = useI18n();
  const { historySignal } = useLive();
  const { showError } = useFeedback();
  const [rows, setRows] = useState<HistoryEntry[] | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();

  useEffect(() => {
    api.history().then(setRows).catch(showError);
  }, [historySignal, showError]);

  const filters = useMemo(() => filtersFromParams(searchParams), [searchParams]);
  // Changes are applied to the latest filters, not to the copy from the last render: a chip clicked
  // while a typed filter is still being debounced must not undo the other change.
  const latest = useRef(filters);
  latest.current = filters;
  const updateFilters = (change: (current: HistoryFilters) => HistoryFilters) => {
    latest.current = change(latest.current);
    // replace, not push: typing in a filter should not fill the browser's Back history
    setSearchParams(filtersToParams(latest.current), { replace: true });
  };
  const setFilters = (next: HistoryFilters) => updateFilters(() => next);
  const active = activeColumns(filters);
  const shown = useMemo(() => (rows ? applyFilters(rows, filters) : []), [rows, filters]);
  const options = useMemo(() => filterOptions(rows ?? []), [rows]);

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{t('history.title')}</h1>
          <p className="muted">
            {rows && active.length > 0 ? t('historyFilter.showing', { n: shown.length, m: rows.length }) : t('history.subtitle')}
          </p>
        </div>
      </div>
      {rows && rows.length === 0 && <div className="empty">{t('history.empty')}</div>}
      {rows && rows.length > 0 && (
        <>
          <HistoryFilterPanel filters={filters} options={options} onChange={updateFilters} />
          {active.length > 0 && (
            <ActiveFilters filters={filters} active={active} onChange={setFilters} />
          )}
          {shown.length === 0 ? (
            <div className="empty" style={{ marginTop: 14 }}>
              <Icon name="filter" size={26} />
              <div>{t('historyFilter.noMatch')}</div>
              <button className="btn" onClick={() => setFilters(emptyFilters())}>{t('historyFilter.clearAll')}</button>
            </div>
          ) : (
            <div className="table-wrap" style={{ marginTop: 14 }}>
              <table className="table">
                <thead>
                  <tr>
                    {COLUMNS.map((column) => (
                      <th key={column.key} className={active.includes(column.key) ? 'filtered' : undefined}>
                        {t(column.label)}{active.includes(column.key) && <Icon name="filter" size={12} />}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {shown.map((entry) => (
                    <tr key={entry.id}>
                      <td style={{ fontWeight: 600 }}>{entry.recordName}</td>
                      <td><div className="col" style={{ gap: 4 }}><span>{entry.categoryName}</span><ModeBadge mode={entry.mode} /></div></td>
                      <td className="mono"><span className="ltr">{entry.target}</span></td>
                      <td>{entry.startedBy}</td>
                      <td className="mono"><span className="ltr">{formatMs(entry.startedAtMs)}</span></td>
                      <td className="mono"><span className="ltr">{formatMs(entry.endedAtMs)}</span></td>
                      <td><span className="ltr">{formatNumber(entry.messagesSent)} / {formatNumber(entry.totalMessages)}</span><div className="small muted">{formatBytes(entry.bytesSent)}</div></td>
                      <td><Result entry={entry} /></td>
                      <td><Playlist entry={entry} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** One chip per filtered column (click ✕ to drop it), and "Clear all filters". */
function ActiveFilters({ filters, active, onChange }: {
  filters: HistoryFilters; active: FilterColumn[]; onChange: (next: HistoryFilters) => void;
}) {
  const { t } = useI18n();
  const range = (from: number | null, to: number | null, show: (value: number) => string) =>
    from !== null && to !== null ? `${show(from)} – ${show(to)}`
      : from !== null ? `≥ ${show(from)}` : `≤ ${show(to!)}`;
  const playlistLabel = usePlaylistLabel();

  const valueOf: Record<FilterColumn, () => string> = {
    record: () => `“${filters.record.trim()}”`,
    category: () => filters.categories.join(', '),
    target: () => `“${filters.target.trim()}”`,
    startedBy: () => filters.users.join(', '),
    start: () => range(filters.startFrom, filters.startTo, formatMs),
    end: () => range(filters.endFrom, filters.endTo, formatMs),
    messages: () => range(filters.messagesMin, filters.messagesMax, formatNumber),
    result: () => filters.results.map((result) => t(`status.${result}`)).join(', '),
    playlist: () => filters.playlists.map(playlistLabel).join(', '),
  };

  return (
    <div className="row wrap" style={{ marginTop: 12 }}>
      {active.map((column) => {
        const label = COLUMNS.find((candidate) => candidate.key === column)!.label;
        return (
          <span key={column} className="chip filter-chip">
            <Icon name="filter" size={11} />
            <span>{t(label)}: <bdi>{valueOf[column]()}</bdi></span>
            <button className="chip-x" aria-label={t('historyFilter.remove')} title={t('historyFilter.remove')}
              onClick={() => onChange(clearColumn(filters, column))}><Icon name="x" size={11} /></button>
          </span>
        );
      })}
      <button className="btn ghost sm" onClick={() => onChange(emptyFilters())}>{t('historyFilter.clearAll')}</button>
    </div>
  );
}

function Playlist({ entry }: { entry: HistoryEntry }) {
  const { t } = useI18n();
  if (!entry.playlist) return <span className="muted">—</span>;
  return (
    <div className="col" style={{ gap: 4 }}>
      <span>{entry.playlist.name ?? <span className="muted">{t('historyFilter.unnamedPlaylist')}</span>}</span>
      <span className="badge neutral" style={{ alignSelf: 'flex-start' }}>{t('history.itemOf', { n: entry.playlist.index, m: entry.playlist.total })}</span>
    </div>
  );
}

function Result({ entry }: { entry: HistoryEntry }) {
  const { t, tc } = useI18n();
  if (entry.result === 'COMPLETED') return <span className="badge ok">{t('result.COMPLETED')}</span>;
  if (entry.result === 'STOPPED') return <span className="badge warn">{t('result.STOPPED', { by: entry.stoppedBy ?? '' })}</span>;
  return (
    <div className="col" style={{ gap: 4, maxWidth: 280 }}>
      <span className="badge bad" style={{ alignSelf: 'flex-start' }}>{t('result.FAILED')}</span>
      {entry.failureCode && <span className="small text-bad">{tc('err', entry.failureCode, entry.failureParams ?? {})}</span>}
    </div>
  );
}
