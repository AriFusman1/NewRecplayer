import { useState } from 'react';
import { api } from '../../api/client';
import type { PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import { Modal } from '../common/Modal';
import { TimeField } from '../common/TimeField';

/** Only name, description and times are editable; times are re-validated by the server. */
export function EditRecordDialog({ record, onClose }: { record: PcapRecord; onClose: () => void }) {
  const { t } = useI18n();
  const { run, busy } = useAction();
  const [name, setName] = useState(record.name);
  const [description, setDescription] = useState(record.description);
  const [startUs, setStartUs] = useState<number | null>(record.startUs);
  const [endUs, setEndUs] = useState<number | null>(record.endUs);
  const valid = name.trim() && startUs !== null && endUs !== null;

  const save = async () => {
    if (!valid) return;
    const saved = await run(() => api.updateRecord(record.id, { name: name.trim(), description, startUs: startUs!, endUs: endUs! }),
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
      <div className="field">
        <label>{t('upload.name')}</label>
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="field">
        <label>{t('upload.description')}</label>
        <textarea className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <div className="grid-2">
        <TimeField label={t('upload.start')} valueUs={record.startUs} onChange={setStartUs} />
        <TimeField label={t('upload.end')} valueUs={record.endUs} isEnd onChange={setEndUs} />
      </div>
    </Modal>
  );
}
