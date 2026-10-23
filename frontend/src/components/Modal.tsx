import { useEffect, type ReactNode } from 'react';
import { Icon } from './Icon';

interface Props {
  title: ReactNode;
  icon?: 'error' | 'warning' | 'info' | 'success';
  children: ReactNode;
  footer?: ReactNode;
  onClose?: () => void;
  wide?: boolean;
}

const ICONS = { error: 'alert', warning: 'alert', info: 'info', success: 'check' } as const;

export function Modal({ title, icon, children, footer, onClose, wide }: Props) {
  useEffect(() => {
    if (!onClose) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose?.()}>
      <div className={`modal${wide ? ' wide' : ''}`} role="dialog" aria-modal="true">
        <div className="modal-head">
          {icon && <div className={`icon-circle ${icon}`}><Icon name={ICONS[icon]} size={18} /></div>}
          <h2 className="grow">{title}</h2>
          {onClose && <button className="btn ghost icon sm" onClick={onClose} aria-label="close"><Icon name="x" /></button>}
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

/** Yes/no confirmation. */
export function Confirm({ title, body, confirmLabel, danger, onConfirm, onCancel, cancelLabel, busy }: {
  title: string; body: ReactNode; confirmLabel: string; cancelLabel: string; danger?: boolean; busy?: boolean;
  onConfirm: () => void; onCancel: () => void;
}) {
  return (
    <Modal title={title} icon={danger ? 'warning' : 'info'} onClose={onCancel}
      footer={<>
        <button className="btn ghost" onClick={onCancel}>{cancelLabel}</button>
        <button className={`btn ${danger ? 'danger solid' : 'primary'}`} disabled={busy} onClick={onConfirm} autoFocus>{confirmLabel}</button>
      </>}>
      <div className="pre">{body}</div>
    </Modal>
  );
}
