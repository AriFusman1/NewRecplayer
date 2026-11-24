import { useMemo, useState } from 'react';
import type { Mode } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { toggleIn } from '../../util/list';
import { matchesQuery } from '../../util/records';
import { formatDuration, formatNumber } from '../../util/time';
import { ModeBadge } from '../common/Badges';
import { Icon } from '../common/Icon';
import { Modal } from '../common/Modal';

/** Choose several records (search + category filter) to append to the playlist, in the order ticked. */
export function RecordPicker({ onAdd, onClose }: { onAdd: (list: { recordId: string; mode: Mode }[]) => void; onClose: () => void }) {
  const { t } = useI18n();
  const { catalog, categoryById, modeOf } = useCatalog();
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [picked, setPicked] = useState<string[]>([]);

  const records = useMemo(() => catalog.records
    .filter((record) => categoryById.has(record.categoryId) && matchesQuery(record, query) && (!categoryId || record.categoryId === categoryId))
    .sort((left, right) => left.name.localeCompare(right.name)), [catalog.records, categoryById, query, categoryId]);

  /** 1-based position of each picked record, in the order ticked. */
  const order = useMemo(() => new Map(picked.map((id, i) => [id, i + 1])), [picked]);
  const allVisible = records.length > 0 && records.every((record) => order.has(record.id));
  const toggleAll = () => {
    const visible = new Set(records.map((record) => record.id));
    setPicked((current) => (allVisible ? current.filter((id) => !visible.has(id)) : [...current, ...records.map((record) => record.id).filter((id) => !order.has(id))]));
  };

  const add = () => {
    onAdd(picked.flatMap((id) => {
      const mode = modeOf(id);
      return mode ? [{ recordId: id, mode }] : [];
    }));
    onClose();
  };

  return (
    <Modal title={t('picker.title')} onClose={onClose} wide
      footer={<>
        <span className="small muted grow">{t('picker.orderHint')}</span>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={picked.length === 0} onClick={add}><Icon name="plus" size={14} />{t('picker.add', { n: picked.length })}</button>
      </>}>
      <div className="row wrap" style={{ gap: 8 }}>
        <div className="search field grow" style={{ minWidth: 200 }}>
          <Icon name="search" />
          <input className="input" autoFocus value={query} placeholder={t('player.search')} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <select className="select" style={{ width: 'auto' }} value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">{t('player.allCategories')}</option>
          {catalog.categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
        </select>
      </div>
      {records.length === 0 ? <div className="empty">{t('player.noMatch')}</div> : (
        <div className="picker-list">
          <label className="picker-row head">
            <input type="checkbox" checked={allVisible} onChange={toggleAll} />
            <span className="small muted">{t('picker.selectAll', { n: records.length })}</span>
          </label>
          {records.map((record) => {
            const cat = categoryById.get(record.categoryId)!;
            const position = order.get(record.id);
            return (
              <label key={record.id} className={`picker-row${position ? ' on' : ''}`}>
                <input type="checkbox" checked={!!position} onChange={() => setPicked((current) => toggleIn(current, record.id))} />
                <span className="picker-order">{position ?? ''}</span>
                <div className="col tight">
                  <div className="row"><span className="ellipsis strong">{record.name}</span><ModeBadge mode={cat.mode} /></div>
                  <span className="small muted">{cat.name} · {formatDuration(record.stats.durationUs)} · {t('upload.messages')} {formatNumber(record.stats.messageCount)}</span>
                </div>
              </label>
            );
          })}
        </div>
      )}
    </Modal>
  );
}
