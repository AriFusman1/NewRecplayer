import { useState } from 'react';
import { useI18n } from '../../i18n';
import { useNickname } from '../../state/nickname';
import { Modal } from '../common/Modal';

/** Asked on first visit (no close button then) and from the header. */
export function NicknameDialog({ onDone }: { onDone?: () => void }) {
  const { t } = useI18n();
  const { nickname, setNickname } = useNickname();
  const [value, setValue] = useState(nickname);
  const save = () => {
    if (!value.trim()) return;
    setNickname(value);
    onDone?.();
  };
  return (
    <Modal title={t('nick.title')} icon="info" onClose={nickname ? onDone : undefined}
      footer={<button className="btn primary" disabled={!value.trim()} onClick={save}>{t('nick.save')}</button>}>
      <p>{t('nick.body')}</p>
      <div className="field">
        <label>{t('header.nickname')}</label>
        <input className="input" autoFocus maxLength={40} value={value} placeholder={t('nick.placeholder')}
          onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && save()} />
      </div>
    </Modal>
  );
}
