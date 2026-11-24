import { memo, useState, type ReactNode } from 'react';
import { api } from '../../api/client';
import type { PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { usePlaylist } from '../../state/playlist';
import { formatBytes, formatDuration, formatMs, formatNumber, formatUs } from '../../util/time';
import { ModeBadge, Warnings } from '../common/Badges';
import { MiniBars } from '../graphs/BarGraph';
import { EditRecordDialog } from './EditRecordDialog';
import { Icon } from '../common/Icon';
import { Confirm } from '../common/Modal';
import { PlayDialog } from '../playback/PlayDialog';

interface Props {
  record: PcapRecord;
  /** Chosen for "Play together". */
  selected?: boolean;
  /** False when the selection is full (and this card is not in it). */
  canSelect?: boolean;
  /** Must be a stable callback, or the memo is useless. */
  onToggleSelect?: (recordId: string) => void;
}

/** One record with all its info and actions. Memoized: the player page re-renders on every keystroke. */
export const RecordCard = memo(function RecordCard({ record, selected = false, canSelect = true, onToggleSelect }: Props) {
  const { t } = useI18n();
  const { categoryById, tagById, fileById } = useCatalog();
  const { add } = usePlaylist();
  const { toast } = useFeedback();
  const { run } = useAction();
  const [dialog, setDialog] = useState<'play' | 'edit' | 'delete' | null>(null);
  const category = categoryById.get(record.categoryId);
  const file = fileById.get(record.fileId);
  const stats = record.stats;
  if (!category) return null;

  const remove = async () => {
    await run(() => api.deleteRecord(record.id));
    setDialog(null);
  };

  return (
    <article className={`card record-card${selected ? ' selected' : ''}`}>
      <div className="row between" style={{ alignItems: 'flex-start' }}>
        <div className="col grow" style={{ gap: 6 }}>
          <div className="name">{record.name}</div>
          <div className="row wrap">
            <span className="chip"><Icon name="folder" size={12} />{category.name}</span>
            <ModeBadge mode={category.mode} />
            {record.tagIds.map((id) => tagById.get(id)).filter(Boolean).map((tag) => (
              <span key={tag!.id} className={`chip tag${tag!.categoryId ? '' : ' global'}`}><bdi>#{tag!.name}</bdi></span>
            ))}
          </div>
        </div>
        {onToggleSelect && (
          <label className={`select-box${canSelect || selected ? '' : ' disabled'}`}
            title={canSelect || selected ? t('select.toggle') : t('select.full')}>
            <input type="checkbox" checked={selected} disabled={!canSelect && !selected}
              onChange={() => onToggleSelect(record.id)} aria-label={t('select.toggle')} />
          </label>
        )}
      </div>
      {record.description && <div className="desc">{record.description}</div>}
      <MiniBars histogram={stats.histogram} />
      <div className="meta">
        <Meta label={t('card.range')} value={<span className="mono ltr">{formatUs(record.startUs)}<br />{formatUs(record.endUs)}</span>} wide />
        <Meta label={t('card.duration')} value={<span className="ltr">{formatDuration(stats.durationUs)}</span>} />
        <Meta label={t('card.messages')} value={<>{formatNumber(stats.messageCount)} · {formatBytes(stats.bytes)}{stats.protocol === 'TCP' && <span className="muted"> · {t('card.connections', { n: stats.connections })}</span>}</>} />
        <Meta label={t('card.ports')} value={<span className="mono">{record.ports.join(', ')}</span>} />
        <Meta label={t('card.ip')} value={record.ip ? <span className="mono ltr">{record.ip}</span> : <span className="muted">{t('card.allIps')}</span>} />
        <Meta label={t('card.file')} value={file ? <span className="ellipsis" title={file.originalName}>{file.originalName} · {formatBytes(file.size)}</span> : '—'} wide />
      </div>
      <div className="small muted">{t('card.uploaded', { name: record.createdBy || '—', time: formatMs(record.createdAtMs) })}</div>
      <Warnings items={stats.warnings} />
      <div className="actions">
        <button className="btn primary sm" onClick={() => setDialog('play')}><Icon name="play" size={12} />{t('common.play')}</button>
        <button className="btn sm" onClick={() => { add(record.id, category.mode); toast('info', t('playlist.added', { name: record.name })); }}>
          <Icon name="plus" size={14} />{t('card.addToPlaylist')}</button>
        <div className="grow" />
        <button className="btn ghost icon sm" title={t('common.edit')} onClick={() => setDialog('edit')}><Icon name="edit" /></button>
        <a className="btn ghost icon sm" title={t('common.download')} href={api.downloadUrl(record.id)}><Icon name="download" /></a>
        <button className="btn ghost icon sm" title={t('common.delete')} onClick={() => setDialog('delete')}><Icon name="trash" /></button>
      </div>

      {dialog === 'play' && <PlayDialog record={record} mode={category.mode} onClose={() => setDialog(null)} />}
      {dialog === 'edit' && <EditRecordDialog record={record} onClose={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <Confirm title={t('del.title')} body={t('del.body', { name: record.name })} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')} onConfirm={remove} onCancel={() => setDialog(null)} />
      )}
    </article>
  );
});

function Meta({ label, value, wide }: { label: string; value: ReactNode; wide?: boolean }) {
  return (
    <div style={{ gridColumn: wide ? '1 / -1' : undefined, minWidth: 0 }}>
      <div className="label">{label}</div>
      <div>{value}</div>
    </div>
  );
}
