import { useState } from 'react';
import { api } from '../../api/client';
import type { TagRule, TagRulesView } from '../../api/types';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import { toggleIn } from '../../util/list';
import { Warnings } from '../common/Badges';
import { Icon } from '../common/Icon';
import { Confirm } from '../common/Modal';
import { ConditionLine } from './ConditionLine';
import { RuleEditorDialog } from './RuleEditorDialog';

interface Props {
  view: TagRulesView;
  selectedIds: string[];
  onSelected: (ids: string[]) => void;
  /** Reload the category's rules after a change. */
  onChanged: () => void;
}

/** The category's saved rules with a checkbox each (include in this run), and create / edit / delete. */
export function RulesPicker({ view, selectedIds, onSelected, onChanged }: Props) {
  const { t } = useI18n();
  const { run } = useAction();
  const [editing, setEditing] = useState<TagRule | 'new' | null>(null);
  const [deleting, setDeleting] = useState<TagRule | null>(null);

  const remove = async (rule: TagRule) => {
    await run(() => api.deleteTagRule(view.categoryId, rule.id!));
    setDeleting(null);
    onSelected(selectedIds.filter((id) => id !== rule.id));
    onChanged();
  };

  return (
    <div className="stack">
      {/* the ICD changed: a warning; a rule that no longer fits the ICD: an error */}
      <Warnings items={view.warnings.filter((warning) => warning.code === 'TAGRULE_ICD_MISMATCH')} />
      <Warnings kind="err" items={view.warnings.filter((warning) => warning.code !== 'TAGRULE_ICD_MISMATCH')} />
      {view.rules.length === 0 && <p className="muted small">{t('at.noRules')}</p>}
      {view.rules.length > 1 && (
        <div className="row" style={{ gap: 6 }}>
          <button className="btn ghost sm" onClick={() => onSelected(view.rules.map((rule) => rule.id!))}>{t('at.tickAll')}</button>
          <button className="btn ghost sm" disabled={selectedIds.length === 0} onClick={() => onSelected([])}>{t('at.tickNone')}</button>
        </div>
      )}
      {view.rules.map((rule) => (
        <div key={rule.id} className={`row rule-row${selectedIds.includes(rule.id!) ? ' ticked' : ''}`} style={{ gap: 10, alignItems: 'flex-start' }}>
          <label className="row grow" style={{ gap: 10, alignItems: 'flex-start', minWidth: 0, cursor: 'pointer' }}>
            <input type="checkbox" checked={selectedIds.includes(rule.id!)} style={{ marginTop: 4 }}
              onChange={() => onSelected(toggleIn(selectedIds, rule.id!))} />
            <div className="grow" style={{ minWidth: 0 }}>
              <div className="strong"><bdi>{rule.name}</bdi></div>
              {rule.conditions.map((condition, i) => <ConditionLine key={i} condition={condition} tree={view.tree} first={i === 0} />)}
            </div>
          </label>
          <button className="btn ghost icon sm" title={t('common.edit')} disabled={!view.tree} onClick={() => setEditing(rule)}><Icon name="edit" /></button>
          <button className="btn ghost icon sm" title={t('common.delete')} onClick={() => setDeleting(rule)}><Icon name="trash" /></button>
        </div>
      ))}
      {view.tree && (
        <div>
          <button className="btn sm" onClick={() => setEditing('new')}><Icon name="plus" size={14} />{t('at.newRule')}</button>
        </div>
      )}

      {editing && view.tree && (
        <RuleEditorDialog categoryId={view.categoryId} tree={view.tree} rule={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            if (saved.id && !selectedIds.includes(saved.id)) onSelected([...selectedIds, saved.id]);
            onChanged();
          }} />
      )}
      {deleting && (
        <Confirm title={t('at.deleteRuleTitle')} body={t('at.deleteRuleBody', { name: deleting.name })} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')} onConfirm={() => void remove(deleting)} onCancel={() => setDeleting(null)} />
      )}
    </div>
  );
}
