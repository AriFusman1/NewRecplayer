import { useState } from 'react';
import { api } from '../../api/client';
import type { Category } from '../../api/types';
import { Modal } from '../../components/common/Modal';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';

/**
 * Deleting a category that has records: move them to another category with the same connection
 * mode (the default when there is one), or delete them with it.
 */
export function DeleteCategoryDialog({ category, recordCount, onClose }: {
  category: Category; recordCount: number; onClose: () => void;
}) {
  const { t } = useI18n();
  const { catalog } = useCatalog();
  const { run, busy } = useAction();
  const targets = catalog.categories.filter((other) => other.id !== category.id && other.mode === category.mode);
  const [moveTo, setMoveTo] = useState(targets[0]?.id ?? '');
  const [choice, setChoice] = useState<'move' | 'delete'>(recordCount > 0 && targets.length > 0 ? 'move' : 'delete');
  const moving = recordCount > 0 && choice === 'move';
  const target = targets.find((other) => other.id === moveTo);

  const confirm = async () => {
    const done = await run(() => api.deleteCategory(category.id, moving ? moveTo : undefined),
      moving ? t('cat.moved', { n: recordCount, name: target?.name ?? '' }) : t('cat.deleted', { name: category.name }));
    if (done) onClose();
  };

  return (
    <Modal title={t('cat.deleteTitle')} icon="warning" onClose={onClose}
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className={`btn ${moving ? 'primary' : 'danger solid'}`} disabled={busy || (moving && !target)} onClick={confirm} autoFocus>
          {moving ? t('cat.moveAndDelete') : t('common.delete')}</button>
      </>}>
      {recordCount === 0 ? <p>{t('cat.deleteEmpty', { name: category.name })}</p> : (
        <div className="col" style={{ gap: 12 }}>
          <p>{t('cat.deleteHasRecords', { name: category.name, n: recordCount })}</p>
          <label className={`choice${choice === 'move' ? ' on' : ''}${targets.length === 0 ? ' disabled' : ''}`}>
            <input type="radio" name="delete-choice" checked={choice === 'move'} disabled={targets.length === 0} onChange={() => setChoice('move')} />
            <div className="col grow" style={{ gap: 8 }}>
              <strong>{t('cat.moveTitle')}</strong>
              {targets.length === 0 ? <span className="hint">{t('cat.noMoveTarget', { mode: t(`mode.${category.mode}`) })}</span> : (
                <select className="select" value={moveTo} onChange={(e) => { setMoveTo(e.target.value); setChoice('move'); }}>
                  {targets.map((other) => <option key={other.id} value={other.id}>{other.name}</option>)}
                </select>
              )}
            </div>
          </label>
          <label className={`choice danger${choice === 'delete' ? ' on' : ''}`}>
            <input type="radio" name="delete-choice" checked={choice === 'delete'} onChange={() => setChoice('delete')} />
            <div className="col grow" style={{ gap: 4 }}>
              <strong>{t('cat.deleteAllTitle', { n: recordCount })}</strong>
              <span className="hint">{t('cat.deleteAllHint')}</span>
            </div>
          </label>
        </div>
      )}
    </Modal>
  );
}
