import { useI18n } from '../i18n';
import { useFeedback } from '../state/feedback';
import { Icon } from './Icon';
import { Modal } from './Modal';

/** Renders toasts and the current popup. */
export function FeedbackView() {
  const { toasts, current, dismissPopup, dismissToast } = useFeedback();
  const { t } = useI18n();
  return (
    <>
      <div className="toasts" aria-live="polite">
        {toasts.map((x) => (
          <div key={x.id} className={`toast ${x.level}`} onClick={() => dismissToast(x.id)}>
            <Icon name={x.level === 'success' ? 'check' : x.level === 'info' ? 'info' : 'alert'} />
            <div className="grow">{x.text}</div>
          </div>
        ))}
      </div>
      {current && (
        <Modal title={current.title} icon={current.level} onClose={dismissPopup}
          footer={<button className="btn primary" onClick={dismissPopup} autoFocus>{t('common.ok')}</button>}>
          <div className="pre">{current.text}</div>
        </Modal>
      )}
    </>
  );
}
