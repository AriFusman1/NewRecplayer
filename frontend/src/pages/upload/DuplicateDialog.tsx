import { useState } from 'react';
import type { SameFile } from '../../api/types';
import { Modal } from '../../components/common/Modal';
import { useI18n } from '../../i18n';

/** The server's 409 DUPLICATE answer. */
export interface Duplicate {
  nameConflict: string | null;
  sameFile: SameFile | null;
  existingFileId: string;
}

interface Props {
  duplicate: Duplicate;
  /** Save anyway as a new record (the name gets a number if taken). */
  onSaveAsNew: () => void;
  /** Save a new record that reuses the stored file. */
  onReuseFile: (fileId: string) => void;
  onCancel: () => void;
}

/** Asks whether this is the same capture; if so, offers to reuse the stored file. */
export function DuplicateDialog({ duplicate, onSaveAsNew, onReuseFile, onCancel }: Props) {
  const { t } = useI18n();
  const [step, setStep] = useState<'ask' | 'reuse'>('ask');

  if (step === 'reuse') {
    return (
      <Modal title={t('dup.reuseTitle')} icon="info" onClose={onCancel}
        footer={<>
          <button className="btn ghost" onClick={onCancel}>{t('common.cancel')}</button>
          <button className="btn primary" onClick={() => onReuseFile(duplicate.existingFileId)}>{t('dup.reuse')}</button>
        </>}>
        <p>{t('dup.reuseBody')}</p>
      </Modal>
    );
  }
  return (
    <Modal title={t('dup.title')} icon="warning" onClose={onCancel}
      footer={<>
        <button className="btn ghost" onClick={onCancel}>{t('common.cancel')}</button>
        <button className="btn" onClick={onSaveAsNew}>{t('dup.no')}</button>
        <button className="btn primary" onClick={() => setStep('reuse')}>{t('dup.yes')}</button>
      </>}>
      {duplicate.nameConflict && <p>{t('dup.nameExists', { name: duplicate.nameConflict })}</p>}
      {duplicate.sameFile && <p>{t('dup.sameFile', { file: duplicate.sameFile.originalName, records: duplicate.sameFile.records.join(', ') })}</p>}
      <strong>{t('dup.question')}</strong>
    </Modal>
  );
}
