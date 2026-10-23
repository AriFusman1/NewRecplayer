import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { HistoryEntry } from '../api/types';
import { ModeBadge } from '../components/Badges';
import { useI18n } from '../i18n';
import { useFeedback } from '../state/feedback';
import { useLive } from '../state/live';
import { formatBytes, formatMs, formatNumber } from '../util/time';

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
              {rows.map((h) => (
                <tr key={h.id}>
                  <td style={{ fontWeight: 600 }}>{h.recordName}</td>
                  <td><div className="col" style={{ gap: 4 }}><span>{h.categoryName}</span><ModeBadge mode={h.mode} /></div></td>
                  <td className="mono"><span className="ltr">{h.target}</span></td>
                  <td>{h.startedBy}</td>
                  <td className="mono"><span className="ltr">{formatMs(h.startedAtMs)}</span></td>
                  <td className="mono"><span className="ltr">{formatMs(h.endedAtMs)}</span></td>
                  <td><span className="ltr">{formatNumber(h.messagesSent)} / {formatNumber(h.totalMessages)}</span><div className="small muted">{formatBytes(h.bytesSent)}</div></td>
                  <td><Result h={h} /></td>
                  <td>{h.playlist ? <span className="badge neutral">{t('history.itemOf', { n: h.playlist.index, m: h.playlist.total })}</span> : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Result({ h }: { h: HistoryEntry }) {
  const { t, tc } = useI18n();
  if (h.result === 'COMPLETED') return <span className="badge ok">{t('result.COMPLETED')}</span>;
  if (h.result === 'STOPPED') return <span className="badge warn">{t('result.STOPPED', { by: h.stoppedBy ?? '' })}</span>;
  return (
    <div className="col" style={{ gap: 4, maxWidth: 280 }}>
      <span className="badge bad" style={{ alignSelf: 'flex-start' }}>{t('result.FAILED')}</span>
      {h.failureCode && <span className="small" style={{ color: '#ffb3bb' }}>{tc('err', h.failureCode, h.failureParams ?? {})}</span>}
    </div>
  );
}
