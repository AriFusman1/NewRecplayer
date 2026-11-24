import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { HistoryEntry } from '../../api/types';
import { ModeBadge } from '../../components/common/Badges';
import { Icon } from '../../components/common/Icon';
import { useI18n, type MessageKey } from '../../i18n';
import { useFeedback } from '../../state/feedback';
import { useLive } from '../../state/live';
import {
  activeColumns, applyFilters, emptyFilters, filtersFromParams, filtersToParams, playlistText,
  type FilterColumn, type HistoryFilters, type PlaylistLabels,
} from '../../util/historyFilters';
import { formatBytes, formatMs, formatNumber } from '../../util/time';
import { HistoryFilterRow } from './HistoryFilterRow';

/** The table's columns, in order: header text and share of the table's width. */
const COLUMNS: { key: FilterColumn; label: MessageKey; width: string }[] = [
  { key: 'record', label: 'history.record', width: '12%' },
  { key: 'category', label: 'history.category', width: '11%' },
  { key: 'target', label: 'history.target', width: '11%' },
  { key: 'startedBy', label: 'history.startedBy', width: '8%' },
  { key: 'start', label: 'history.start', width: '14.5%' },
  { key: 'end', label: 'history.end', width: '14.5%' },
  { key: 'messages', label: 'history.messages', width: '9%' },
  { key: 'result', label: 'history.result', width: '10%' },
  { key: 'playlist', label: 'history.playlist', width: '10%' },
];

/**
 * The last 20 runs (stored in S3), with a filter under each column header. The list is small and
 * already in the browser, so filtering happens here; the filters live in the URL query string.
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
  // Changes are applied to the latest filters, not to the copy from the last render: a change made
  // while a typed filter is still being debounced must not undo the other change.
  const latest = useRef(filters);
  latest.current = filters;
  const updateFilters = (change: (current: HistoryFilters) => HistoryFilters) => {
    latest.current = change(latest.current);
    // replace, not push: typing in a filter should not fill the browser's Back history
    setSearchParams(filtersToParams(latest.current), { replace: true });
  };
  const labels: PlaylistLabels = useMemo(
    () => ({ none: t('historyFilter.noPlaylist'), unnamed: t('historyFilter.unnamedPlaylist') }), [t]);
  const active = activeColumns(filters);
  const shown = useMemo(() => (rows ? applyFilters(rows, filters, labels) : []), [rows, filters, labels]);

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
        <div className="table-wrap">
          <table className="table history-table">
            <colgroup>
              {COLUMNS.map((column) => <col key={column.key} style={{ width: column.width }} />)}
              <col className="clear-all-col" />
            </colgroup>
            <thead>
              <tr>
                {COLUMNS.map((column) => (
                  <th key={column.key} className={active.includes(column.key) ? 'filtered' : undefined}>
                    {t(column.label)}{active.includes(column.key) && <Icon name="filter" size={12} />}
                  </th>
                ))}
                <th />
              </tr>
              <HistoryFilterRow rows={rows} filters={filters} labels={labels} onChange={updateFilters}
                anyActive={active.length > 0} onClearAll={() => updateFilters(() => emptyFilters())} />
            </thead>
            <tbody>
              {shown.length === 0 ? (
                <tr className="no-match">
                  <td colSpan={COLUMNS.length + 1}>
                    <div className="empty">
                      <Icon name="filter" size={26} />
                      <div>{t('historyFilter.noMatch')}</div>
                      <button className="btn" onClick={() => updateFilters(() => emptyFilters())}>{t('historyFilter.clearAll')}</button>
                    </div>
                  </td>
                </tr>
              ) : shown.map((entry) => (
                <tr key={entry.id}>
                  <td><Ellipsis text={entry.recordName} strong /></td>
                  <td><div className="col" style={{ gap: 4, minWidth: 0 }}><Ellipsis text={entry.categoryName} /><ModeBadge mode={entry.mode} /></div></td>
                  <td className="mono"><Ellipsis text={entry.target} ltr /></td>
                  <td><Ellipsis text={entry.startedBy} /></td>
                  <td className="mono"><Ellipsis text={formatMs(entry.startedAtMs)} ltr /></td>
                  <td className="mono"><Ellipsis text={formatMs(entry.endedAtMs)} ltr /></td>
                  <td><span className="ltr">{formatNumber(entry.messagesSent)} / {formatNumber(entry.totalMessages)}</span><div className="small muted">{formatBytes(entry.bytesSent)}</div>
                    {(entry.missedMessages ?? 0) > 0 && <div className="small missed" title={t('live.missedHint')}>{t('live.missedShort', { n: formatNumber(entry.missedMessages!) })}</div>}</td>
                  <td><Result entry={entry} /></td>
                  <td><Playlist entry={entry} labels={labels} /></td>
                  <td />
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** One line of text, cut with "…" when the column is too narrow; the full text is in the tooltip. */
function Ellipsis({ text, strong, ltr }: { text: string; strong?: boolean; ltr?: boolean }) {
  return (
    <div className={`ellipsis${ltr ? ' ltr-block' : ''}`} title={text} style={strong ? { fontWeight: 600 } : undefined}>
      {text}
    </div>
  );
}

function Playlist({ entry, labels }: { entry: HistoryEntry; labels: PlaylistLabels }) {
  const { t } = useI18n();
  if (!entry.playlist) return <span className="muted">—</span>;
  return (
    <div className="col" style={{ gap: 4, minWidth: 0 }}>
      <Ellipsis text={playlistText(entry, labels)} />
      <span className="badge neutral" style={{ alignSelf: 'flex-start' }}>{t('history.itemOf', { n: entry.playlist.index, m: entry.playlist.total })}</span>
    </div>
  );
}

function Result({ entry }: { entry: HistoryEntry }) {
  const { t, tc } = useI18n();
  if (entry.result === 'COMPLETED') return <span className="badge ok">{t('result.COMPLETED')}</span>;
  if (entry.result === 'STOPPED') {
    const text = t('result.STOPPED', { by: entry.stoppedBy ?? '' });
    return <span className="badge warn ellipsis" title={text} style={{ maxWidth: '100%' }}>{text}</span>;
  }
  const reason = entry.failureCode ? tc('err', entry.failureCode, entry.failureParams ?? {}) : '';
  return (
    <div className="col" style={{ gap: 4, minWidth: 0 }}>
      <span className="badge bad" style={{ alignSelf: 'flex-start' }}>{t('result.FAILED')}</span>
      {reason && <span className="small text-bad ellipsis" title={reason}>{reason}</span>}
    </div>
  );
}
