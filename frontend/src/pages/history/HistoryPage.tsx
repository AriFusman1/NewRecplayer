import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { HistoryEntry } from '../../api/types';
import { ModeBadge } from '../../components/common/Badges';
import { useI18n } from '../../i18n';
import { useFeedback } from '../../state/feedback';
import { useLive } from '../../state/live';
import { formatBytes, formatMs, formatNumber } from '../../util/time';

/** The last 20 runs (stored in S3). */
export function HistoryPage() {
  const { t } = useI18n();
  const { historySignal } = useLive();
  const { showError } = useFeedback();
  const [rows, setRows] = useState<HistoryEntry[] | null>(null);

  useEffect(() => {
    api.history().then(setRows).catch(showError);
  }, [historySignal, showError]);

  return (
    <div>
      <div className="page-head">
        <div><h1>{t('history.title')}</h1><p className="muted">{t('history.subtitle')}</p></div>
      </div>
      {rows && rows.length === 0 && <div className="empty">{t('history.empty')}</div>}
      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>{t('history.record')}</th><th>{t('history.category')}</th><th>{t('history.target')}</th>
                <th>{t('history.startedBy')}</th><th>{t('history.start')}</th><th>{t('history.end')}</th>
                <th>{t('history.messages')}</th><th>{t('history.result')}</th><th>{t('history.playlist')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((entry) => (
                <tr key={entry.id}>
                  <td style={{ fontWeight: 600 }}>{entry.recordName}</td>
                  <td><div className="col" style={{ gap: 4 }}><span>{entry.categoryName}</span><ModeBadge mode={entry.mode} /></div></td>
                  <td className="mono"><span className="ltr">{entry.target}</span></td>
                  <td>{entry.startedBy}</td>
                  <td className="mono"><span className="ltr">{formatMs(entry.startedAtMs)}</span></td>
                  <td className="mono"><span className="ltr">{formatMs(entry.endedAtMs)}</span></td>
                  <td><span className="ltr">{formatNumber(entry.messagesSent)} / {formatNumber(entry.totalMessages)}</span><div className="small muted">{formatBytes(entry.bytesSent)}</div></td>
                  <td><Result entry={entry} /></td>
                  <td>{entry.playlist ? <span className="badge neutral">{t('history.itemOf', { n: entry.playlist.index, m: entry.playlist.total })}</span> : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
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
