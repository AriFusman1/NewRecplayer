import type { UploadResult } from '../../api/types';
import { Warnings } from '../../components/common/Badges';
import { Icon } from '../../components/common/Icon';
import { RangeGraph } from '../../components/graphs/RangeGraph';
import { useI18n } from '../../i18n';
import { formatBytes, formatDuration, formatNumber, formatUs } from '../../util/time';

interface Props {
  upload: UploadResult;
  /** Packets of the chosen ports per timeline bucket, or null. */
  highlight: number[] | null;
  startUs: number | null;
  endUs: number | null;
  onRangeChange: (startUs: number, endUs: number) => void;
}

/** What the capture contains, with the packets-over-time graph used to choose the range. */
export function SummaryCard({ upload, highlight, startUs, endUs, onRangeChange }: Props) {
  const { t } = useI18n();
  const summary = upload.summary;
  return (
    <section className="card">
      <div className="card-title"><h2>{t('upload.summary')}</h2><span className="badge neutral">{summary.format}</span></div>
      <dl className="kv">
        <dt>{t('upload.file')}</dt><dd>{upload.fileName} · {formatBytes(upload.size)}</dd>
        <dt>{t('upload.recording')}</dt><dd><span className="mono ltr">{formatUs(summary.startUs)} → {formatUs(summary.endUs)}</span> <span className="muted">({formatDuration(summary.endUs - summary.startUs)})</span></dd>
        <dt>{t('upload.frames')}</dt><dd>{formatNumber(summary.frames)}</dd>
        <dt>{t('upload.protocols')}</dt><dd>TCP {formatNumber(summary.tcpPackets)} · UDP {formatNumber(summary.udpPackets)}</dd>
        <dt>SHA-256</dt><dd className="mono ltr muted" style={{ fontSize: 11.5 }}>{upload.sha256}</dd>
      </dl>
      {summary.timeline && summary.timeline.length > 0 && !!summary.binUs && (
        <RangeGraph startUs={summary.startUs} endUs={summary.endUs} binUs={summary.binUs} total={summary.timeline} highlight={highlight}
          selectedStartUs={startUs} selectedEndUs={endUs} onChange={onRangeChange} />
      )}
      {upload.sameFile && (
        <div className="alert info" style={{ marginTop: 12 }}><Icon name="info" />
          <span>{t('dup.sameFile', { file: upload.sameFile.originalName, records: upload.sameFile.records.join(', ') || '—' })}</span></div>
      )}
      {summary.warnings.length > 0 && <div style={{ marginTop: 12 }}><Warnings items={summary.warnings} /></div>}
    </section>
  );
}
