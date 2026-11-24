import { memo } from 'react';
import type { LogLine, SessionProgress } from '../../api/types';
import { useI18n } from '../../i18n';
import { formatBytes, formatDuration, formatMs, formatNumber, formatUs } from '../../util/time';
import { Stat } from '../common/Stat';
import { BarGraph } from '../graphs/BarGraph';

/** Live view of the item being played. */
export function LiveView({ progress, onSeek }: { progress: SessionProgress; onSeek?: (positionUs: number) => void }) {
  const { t } = useI18n();
  const live = progress;
  const progressPercent = live.durationUs > 0 ? (live.positionUs / live.durationUs) * 100 : 100;
  return (
    <div className="col" style={{ gap: 12 }}>
      <BarGraph histogram={live.histogram} binUs={live.binUs} durationUs={live.durationUs} positionUs={live.positionUs} onSeek={onSeek} />
      <div className="bars-axis">
        <span>{formatDuration(0)}</span>
        {onSeek && <span className="muted">{t('live.seekHint')}</span>}
        <span>{formatDuration(live.durationUs)}</span>
      </div>
      <div className="progress"><div style={{ width: `${progressPercent}%` }} /></div>
      <div className="stats">
        <Stat label={t('live.elapsed')} value={formatDuration(live.positionUs)} />
        <Stat label={t('live.remaining')} value={formatDuration(live.durationUs - live.positionUs)} />
        <Stat label={t('live.clock')} value={formatUs(live.rangeStartUs + live.positionUs)} mono />
        <Stat label={t('live.messages')} value={`${formatNumber(live.sentMessages)} / ${formatNumber(live.totalMessages)}`} />
        <Stat label={t('live.bytes')} value={formatBytes(live.sentBytes)} />
      </div>
    </div>
  );
}

/**
 * The session log. Memoized on its newest line: sessions are re-sent 5x per second but the log
 * rarely changes.
 */
export const LogView = memo(function LogView({ lines }: { lines: LogLine[] }) {
  const { t, tc } = useI18n();
  if (!lines.length) return null;
  return (
    <div className="field">
      <span className="label">{t('live.log')}</span>
      <div className="log">
        {[...lines].reverse().map((line, i) => (
          <div key={i} className={`line ${line.level}`}>
            <span className="ts">{formatMs(line.tsMs).slice(11)}</span>
            <span>{line.code.startsWith('LOG_') ? tc('log', line.code, line.params) : tc('err', line.code, line.params)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}, (before, after) => before.lines.length === after.lines.length && before.lines[before.lines.length - 1]?.tsMs === after.lines[after.lines.length - 1]?.tsMs);
