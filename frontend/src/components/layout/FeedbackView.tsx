import { useI18n } from '../../i18n';
import { useFeedback } from '../../state/feedback';
import { Icon } from '../common/Icon';
import { Modal } from '../common/Modal';

/** Renders toasts and the current popup. */
export function FeedbackView() {
  const { toasts, current, dismissPopup, dismissToast } = useFeedback();
  const { t } = useI18n();
  return (
    <>
      <div className="toasts" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast ${toast.level}`} onClick={() => dismissToast(toast.id)}>
            <Icon name={toast.level === 'success' ? 'check' : toast.level === 'info' ? 'info' : 'alert'} />
            <div className="grow">{toast.text}</div>
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
