import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { Modal } from '../common/Modal';
import { TimeField } from '../common/TimeField';
import { CategorySelect } from './CategorySelect';
import { TagInput } from './TagInput';

/**
 * Name, description, times, category and tags. The category can only change to one with the same
 * connection mode; times are re-validated by the server.
 */
export function EditRecordDialog({ record, onClose }: { record: PcapRecord; onClose: () => void }) {
  const { t } = useI18n();
  const { recordById, fileById, loaded, modeOf } = useCatalog();
  const file = fileById.get(record.fileId);
  const { toast } = useFeedback();
  const { run, busy } = useAction();
  const [mode] = useState(() => modeOf(record.id));
  const [name, setName] = useState(record.name);
  const [description, setDescription] = useState(record.description);
  const [startUs, setStartUs] = useState<number | null>(record.startUs);
  const [endUs, setEndUs] = useState<number | null>(record.endUs);
  const [categoryId, setCategoryId] = useState(record.categoryId);
  const [tagIds, setTagIds] = useState(record.tagIds);
  const valid = name.trim() && categoryId && startUs !== null && endUs !== null;
  // another user deleted the record (or its category, which deletes the record)
  const gone = loaded && !recordById.has(record.id);

  useEffect(() => {
    if (!gone) return;
    toast('warning', t('edit.recordDeleted', { name: record.name }));
    onClose();
  }, [gone]); // eslint-disable-line react-hooks/exhaustive-deps

  if (gone) return null;

  const save = async () => {
    if (!valid) return;
    const saved = await run(() => api.updateRecord(record.id,
      { name: name.trim(), description, startUs: startUs!, endUs: endUs!, categoryId, tagIds }),
    t('upload.saved', { name: name.trim() }));
    if (saved) onClose();
  };

  return (
    <Modal title={t('edit.title')} onClose={onClose} wide
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!valid || busy} onClick={save}>{busy ? t('common.loading') : t('common.save')}</button>
      </>}>
      <p className="small muted">{t('edit.hint')}</p>
      <div className="grid-2">
        <div className="field">
          <label>{t('upload.name')}</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <CategorySelect value={categoryId} onChange={setCategoryId} lockedMode={mode} />
      </div>
      <div className="field">
        <label>{t('upload.description')}</label>
        <textarea className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <div className="grid-2">
        <TimeField label={t('upload.start')} valueUs={record.startUs} onChange={setStartUs} boundsUs={file} />
        <TimeField label={t('upload.end')} valueUs={record.endUs} isEnd onChange={setEndUs} boundsUs={file} />
      </div>
      <TagInput selected={tagIds} onChange={setTagIds} />
    </Modal>
  );
}
