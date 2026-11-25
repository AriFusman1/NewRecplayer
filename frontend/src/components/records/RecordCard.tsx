import { memo, useState, type ReactNode } from 'react';
import { api } from '../../api/client';
import type { PcapRecord, Tag } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { usePlaylist } from '../../state/playlist';
import { formatBytes, formatDuration, formatMs, formatNumber, formatUs } from '../../util/time';
import { ModeBadge, Warnings } from '../common/Badges';
import { MiniBars } from '../graphs/BarGraph';
import { EditRecordDialog } from './EditRecordDialog';
import { MessageCounts } from '../autotag/MessageCounts';
import { Icon } from '../common/Icon';
import { Confirm } from '../common/Modal';
import { PlayDialog } from '../playback/PlayDialog';
import { useRoom } from '../../state/live';

interface Props {
  record: PcapRecord;
  /** Given in selection mode (bulk tagging): the card shows a checkbox and a click toggles it. */
  onToggleSelect?: (recordId: string) => void;
  selected?: boolean;
}

/** One record with all its info and actions. Memoized: the player page re-renders on every keystroke. */
export const RecordCard = memo(function RecordCard({ record, onToggleSelect, selected = false }: Props) {
  const { t } = useI18n();
  const { catalog, categoryById, tagById, fileById } = useCatalog();
  const { add } = usePlaylist();
  const { toast } = useFeedback();
  const { run } = useAction();
  const [dialog, setDialog] = useState<'play' | 'edit' | 'delete' | null>(null);
  const [showStats, setShowStats] = useState(false);
  /** Set when every replay slot on the server is taken: Play is disabled and says why. */
  const full = useRoom().blockedFor(1);
  const category = categoryById.get(record.categoryId);
  const file = fileById.get(record.fileId);
  const stats = record.stats;
  const tags = record.tagIds.map((id) => tagById.get(id)).filter((tag): tag is Tag => tag !== undefined);
  if (!category) return null;

  /** The delete prompt says whether the capture file goes too: it stays while other records use it. */
  const deleteBody = () => {
    const others = catalog.records.filter((other) => other.fileId === record.fileId && other.id !== record.id).length;
    const fileName = file?.originalName ?? '';
    return [t('del.body', { name: record.name }),
      others > 0 ? t('del.fileKept', { file: fileName, n: others }) : t('del.fileDeleted', { file: fileName }),
      t('del.irreversible')].join('\n\n');
  };

  const remove = async () => {
    await run(() => api.deleteRecord(record.id));
    setDialog(null);
  };

  return (
    <article className={`card record-card${onToggleSelect ? ' selecting' : ''}${selected ? ' selected' : ''}`}
      onClick={onToggleSelect ? (e) => {
        // a dialog renders inside the card; its clicks must not toggle the selection
        if (!(e.target as HTMLElement).closest('.modal-backdrop')) onToggleSelect(record.id);
      } : undefined}>
      <header className="rc-head">
        {onToggleSelect && (
          <input type="checkbox" className="rc-check" checked={selected} aria-label={record.name}
            onClick={(e) => e.stopPropagation()} onChange={() => onToggleSelect(record.id)} />
        )}
        <div className="rc-title">
          <div className="name">{record.name}</div>
          <div className="rc-category"><Icon name="folder" size={13} /><bdi>{category.name}</bdi></div>
        </div>
        <ModeBadge mode={category.mode} />
      </header>
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
      {tags.length > 0 && (
        <div className="rc-tags" aria-label={t('upload.tags')}>
          <Icon name="tag" size={13} />
          {tags.map((tag) => <span key={tag.id} className="tag-pill"><bdi>{tag.name}</bdi></span>)}
        </div>
      )}
      {record.tagging && (
        <div className="rc-tagging">
          <div className="rc-tags" aria-label={t('card.matchedRules')}>
            <Icon name="check" size={13} />
            {record.tagging.matchedRules.map((rule) => <span key={rule} className="chip tag"><bdi>{rule}</bdi></span>)}
            <button className="btn ghost sm" onClick={(e) => { e.stopPropagation(); setShowStats(!showStats); }} aria-expanded={showStats}>
              {showStats ? t('card.hideStats') : t('card.showStats')}</button>
          </div>
          {showStats && (
            <div className="rc-stats" onClick={(e) => e.stopPropagation()}>
              <div className="small muted">
                {t('stats.summary', { n: record.tagging.totalMessages, icd: record.tagging.icdId, time: formatMs(record.tagging.taggedAtMs) })}
                {' · '}{t('stats.problems', { parse: record.tagging.parseErrors, unknown: record.tagging.unknownOpcodes })}
              </div>
              <MessageCounts counts={record.tagging.counts} />
            </div>
          )}
        </div>
      )}
      <div className="small muted">{t('card.uploaded', { name: record.createdBy || '—', time: formatMs(record.createdAtMs) })}</div>
      <Warnings items={stats.warnings} />
      {!onToggleSelect && <div className="actions">
        {/* the tooltip sits on a wrapper: a disabled button gets no mouse events, so it would never show */}
        <span title={full ?? undefined}>
          <button className="btn primary sm" disabled={full !== null} onClick={() => setDialog('play')}><Icon name="play" size={12} />{t('common.play')}</button>
        </span>
        <button className="btn sm" onClick={() => { add(record.id, category.mode); toast('info', t('playlist.added', { name: record.name })); }}>
          <Icon name="plus" size={14} />{t('card.addToPlaylist')}</button>
        <div className="grow" />
        <button className="btn ghost icon sm" title={t('common.edit')} onClick={() => setDialog('edit')}><Icon name="edit" /></button>
        <a className="btn ghost icon sm" title={t('common.download')} href={api.downloadUrl(record.id)}><Icon name="download" /></a>
        <button className="btn ghost icon sm" title={t('common.delete')} onClick={() => setDialog('delete')}><Icon name="trash" /></button>
      </div>}

      {dialog === 'play' && <PlayDialog record={record} mode={category.mode} onClose={() => setDialog(null)} />}
      {dialog === 'edit' && <EditRecordDialog record={record} onClose={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <Confirm title={t('del.title')} body={deleteBody()} danger
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
