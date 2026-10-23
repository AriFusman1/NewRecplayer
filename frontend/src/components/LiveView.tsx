import type { LogLine, SessionProgress } from '../api/types';
import { useI18n } from '../i18n';
import { formatBytes, formatDuration, formatMs, formatNumber, formatUs } from '../util/time';
import { BarGraph } from './BarGraph';

/** Live view of the item being played. */
export function LiveView({ progress }: { progress: SessionProgress }) {
  const { t } = useI18n();
  const p = progress;
  const pct = p.durationUs > 0 ? (p.positionUs / p.durationUs) * 100 : 100;
  return (
    <div className="col" style={{ gap: 12 }}>
      <BarGraph histogram={p.histogram} binUs={p.binUs} durationUs={p.durationUs} positionUs={p.positionUs} />
      <div className="bars-axis">
        <span>{formatDuration(0)}</span>
        <span>{formatDuration(p.durationUs)}</span>
      </div>
      <div className="progress"><div style={{ width: `${pct}%` }} /></div>
      <div className="stats">
        <Stat label={t('live.elapsed')} value={formatDuration(p.positionUs)} />
        <Stat label={t('live.remaining')} value={formatDuration(p.durationUs - p.positionUs)} />
        <Stat label={t('live.clock')} value={formatUs(p.rangeStartUs + p.positionUs)} mono />
        <Stat label={t('live.messages')} value={`${formatNumber(p.sentMessages)} / ${formatNumber(p.totalMessages)}`} />
        <Stat label={t('live.bytes')} value={formatBytes(p.sentBytes)} />
      </div>
    </div>
  );
}

function Stat({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className={`value${mono ? ' mono' : ''}`}><span className="ltr">{value}</span></div>
    </div>
  );
}

export function LogView({ lines }: { lines: LogLine[] }) {
  const { t, tc } = useI18n();
  if (!lines.length) return null;
  return (
    <div className="field">
      <span className="label">{t('live.log')}</span>
      <div className="log">
        {[...lines].reverse().map((l, i) => (
          <div key={i} className={`line ${l.level}`}>
            <span className="ts">{formatMs(l.tsMs).slice(11)}</span>
            <span>{l.code.startsWith('LOG_') ? tc('log', l.code, l.params) : tc('err', l.code, l.params)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
