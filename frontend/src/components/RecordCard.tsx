import { useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { PcapRecord } from '../api/types';
import { useI18n } from '../i18n';
import { useCatalog } from '../state/catalog';
import { useFeedback } from '../state/feedback';
import { usePlaylist } from '../state/playlist';
import { formatBytes, formatDuration, formatMs, formatNumber, formatUs } from '../util/time';
import { ModeBadge, Warnings } from './Badges';
import { MiniBars } from './BarGraph';
import { EditRecordDialog } from './EditRecordDialog';
import { Icon } from './Icon';
import { Confirm } from './Modal';
import { PlayDialog } from './PlayDialog';

/** One record with all its info and actions. */
export function RecordCard({ record }: { record: PcapRecord }) {
  const { t } = useI18n();
  const { categoryById, tagById, fileById } = useCatalog();
  const { add } = usePlaylist();
  const { toast, showError } = useFeedback();
  const [dialog, setDialog] = useState<'play' | 'edit' | 'delete' | null>(null);
  const category = categoryById.get(record.categoryId);
  const file = fileById.get(record.fileId);
  const s = record.stats;
  if (!category) return null;

  const remove = async () => {
    try {
      await api.deleteRecord(record.id);
    } catch (e) {
      showError(e);
    }
    setDialog(null);
  };

  return (
    <article className="card record-card">
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
      </div>
      {record.description && <div className="desc">{record.description}</div>}
      <MiniBars histogram={s.histogram} />
      <div className="meta">
        <Meta label={t('card.range')} value={<span className="mono ltr">{formatUs(record.startUs)}<br />{formatUs(record.endUs)}</span>} wide />
        <Meta label={t('card.duration')} value={<span className="ltr">{formatDuration(s.durationUs)}</span>} />
        <Meta label={t('card.messages')} value={<>{formatNumber(s.messageCount)} · {formatBytes(s.bytes)}{s.protocol === 'TCP' && <span className="muted"> · {t('card.connections', { n: s.connections })}</span>}</>} />
        <Meta label={t('card.ports')} value={<span className="mono">{record.ports.join(', ')}</span>} />
        <Meta label={t('card.ip')} value={record.ip ? <span className="mono ltr">{record.ip}</span> : <span className="muted">{t('card.allIps')}</span>} />
        <Meta label={t('card.file')} value={file ? <span className="ellipsis" title={file.originalName}>{file.originalName} · {formatBytes(file.size)}</span> : '—'} wide />
      </div>
      <div className="small muted">{t('card.uploaded', { name: record.createdBy || '—', time: formatMs(record.createdAtMs) })}</div>
      <Warnings items={s.warnings} />
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
}

function Meta({ label, value, wide }: { label: string; value: ReactNode; wide?: boolean }) {
  return (
    <div style={{ gridColumn: wide ? '1 / -1' : undefined, minWidth: 0 }}>
      <div className="label">{label}</div>
      <div>{value}</div>
    </div>
  );
}
