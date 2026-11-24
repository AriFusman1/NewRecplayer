import { useMemo, useState } from 'react';
import { api } from '../../api/client';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { toggleIn } from '../../util/list';
import { Modal } from '../common/Modal';
import { TagInput } from './TagInput';

/** Adds tags to and removes tags from several records at once. */
export function BulkTagDialog({ recordIds, onClose }: { recordIds: string[]; onClose: () => void }) {
  const { t } = useI18n();
  const { catalog, recordById } = useCatalog();
  const { toast } = useFeedback();
  const { run, busy } = useAction();
  const [add, setAdd] = useState<string[]>([]);
  const [remove, setRemove] = useState<string[]>([]);

  /** Tags on at least one selected record, with how many of them carry it. */
  const present = useMemo(() => {
    const counts = new Map<string, number>();
    recordIds.forEach((id) => recordById.get(id)?.tagIds.forEach((tagId) => counts.set(tagId, (counts.get(tagId) ?? 0) + 1)));
    return catalog.tags.filter((tag) => counts.has(tag.id))
      .sort((left, right) => left.name.localeCompare(right.name))
      .map((tag) => ({ tag, count: counts.get(tag.id)! }));
  }, [catalog.tags, recordById, recordIds]);

  const save = async () => {
    const done = await run(async () => {
      const result = await api.retagRecords(recordIds, add, remove);
      toast('success', t('bulk.updated', { n: result.updated }));
    });
    if (done) onClose();
  };

  return (
    <Modal title={t('bulk.title', { n: recordIds.length })} onClose={onClose} wide
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={busy || (add.length === 0 && remove.length === 0)} onClick={save}>
          {busy ? t('common.loading') : t('common.save')}</button>
      </>}>
      <TagInput label={t('bulk.add')} selected={add} onChange={setAdd} />
      <div className="field">
        <label>{t('bulk.remove')}</label>
        {present.length === 0 ? <span className="hint">{t('bulk.noTags')}</span> : (
          <div className="row wrap">
            {present.map(({ tag, count }) => (
              <button key={tag.id} className={`chip selectable remove${remove.includes(tag.id) ? ' on' : ''}`}
                onClick={() => setRemove(toggleIn(remove, tag.id))}>
                <bdi>#{tag.name}</bdi><span className="muted">{count}/{recordIds.length}</span></button>
            ))}
          </div>
        )}
      </div>
    </Modal>
  );
}
