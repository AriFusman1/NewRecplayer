import { useState } from 'react';
import { api } from '../api/client';
import type { PcapRecord } from '../api/types';
import { useI18n } from '../i18n';
import { useFeedback } from '../state/feedback';
import { Modal } from './Modal';
import { TimeField } from './TimeField';

/** Only name, description and times are editable; times are re-validated by the server. */
export function EditRecordDialog({ record, onClose }: { record: PcapRecord; onClose: () => void }) {
  const { t } = useI18n();
  const { showError, toast } = useFeedback();
  const [name, setName] = useState(record.name);
  const [description, setDescription] = useState(record.description);
  const [startUs, setStartUs] = useState<number | null>(record.startUs);
  const [endUs, setEndUs] = useState<number | null>(record.endUs);
  const [busy, setBusy] = useState(false);
  const valid = name.trim() && startUs !== null && endUs !== null;

  const save = async () => {
    if (!valid) return;
    setBusy(true);
    try {
      const r = await api.updateRecord(record.id, { name: name.trim(), description, startUs: startUs!, endUs: endUs! });
      toast('success', t('upload.saved', { name: r.name }));
      onClose();
    } catch (e) {
      showError(e);
    } finally {
      setBusy(false);
    }
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
