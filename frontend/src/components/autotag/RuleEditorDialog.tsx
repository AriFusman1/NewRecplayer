import { useState } from 'react';
import { api } from '../../api/client';
import type { IcdTree, IcdTreeNode, Operator, TagRule } from '../../api/types';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import {
  draftOf, isList, newCondition, newPredicate, nodesByPath, operatorsFor, ruleOf, SYMBOLS, valueTypeOf,
  type ConditionDraft, type PredicateDraft, type RuleDraft,
} from '../../util/tagRules';
import { opcodeLabel } from '../../util/timefix';
import { Icon } from '../common/Icon';
import { Modal } from '../common/Modal';
import { IcdTreeView } from '../icd/IcdTreeView';

interface Props {
  categoryId: string;
  tree: IcdTree;
  /** The rule to edit; null for a new one. */
  rule: TagRule | null;
  onSaved: (rule: TagRule) => void;
  onClose: () => void;
}

/**
 * Builds a rule from the category's ICD tree. Pick a message type, then add a count condition
 * ("at least N messages of this type") or click fields to add predicates to a field condition
 * ("at least N messages of this type where every predicate holds"). All conditions must hold (AND).
 */
export function RuleEditorDialog({ categoryId, tree, rule, onSaved, onClose }: Props) {
  const { t } = useI18n();
  const { run, busy } = useAction();
  const [draft, setDraft] = useState<RuleDraft>(() => draftOf(rule));
  const [opcode, setOpcode] = useState<number>(tree.messages[0]?.opcode ?? 0);
  const message = tree.messages.find((candidate) => candidate.opcode === opcode) ?? null;

  const setConditions = (conditions: ConditionDraft[]) => setDraft({ ...draft, conditions });
  const updateCondition = (key: number, change: Partial<ConditionDraft>) =>
    setConditions(draft.conditions.map((condition) => (condition.key === key ? { ...condition, ...change } : condition)));

  /** Adds the field to the last field condition on this message, or starts one. */
  const addPredicate = (node: IcdTreeNode) => {
    const last = [...draft.conditions].reverse().find((condition) => condition.kind === 'FIELDS' && condition.opcode === opcode);
    if (last) updateCondition(last.key, { predicates: [...last.predicates, newPredicate(node)] });
    else setConditions([...draft.conditions, { ...newCondition('FIELDS', opcode), predicates: [newPredicate(node)] }]);
  };

  const save = () => run(async () => {
    const body = ruleOf(draft, tree);
    const saved = draft.id ? await api.updateTagRule(categoryId, body) : await api.createTagRule(categoryId, body);
    onSaved(saved);
  }, t('rule.saved'));

  return (
    <Modal wide title={rule ? t('rule.editTitle') : t('rule.newTitle')} onClose={onClose}
      footer={<>
        <button className="btn" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={busy || !draft.name.trim() || draft.conditions.length === 0} onClick={() => void save()}>
          <Icon name="check" />{t('common.save')}</button>
      </>}>
      <div className="stack-lg">
        <label className="field">
          <span className="label">{t('rule.name')}</span>
          <input className="input" value={draft.name} placeholder={t('rule.namePlaceholder')}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        </label>

        <div>
          <span className="section-title">{t('rule.conditions')}</span>
          <p className="small muted" style={{ margin: '4px 0 8px' }}>{t('rule.conditionsHelp')}</p>
          {draft.conditions.length === 0 && <p className="muted small">{t('rule.noConditions')}</p>}
          {draft.conditions.map((condition, i) => (
            <ConditionEditor key={condition.key} number={i + 1} condition={condition} tree={tree}
              onChange={(change) => updateCondition(condition.key, change)}
              onRemove={() => setConditions(draft.conditions.filter((other) => other.key !== condition.key))} />
          ))}
        </div>

        <div className="card flat">
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <span className="label">{t('rule.message')}</span>
            <select className="select" value={opcode} onChange={(e) => setOpcode(Number(e.target.value))}>
              {tree.messages.map((option) => (
                <option key={option.opcode} value={option.opcode}>{option.name} ({opcodeLabel(option.opcode)})</option>
              ))}
            </select>
            <button className="btn sm" onClick={() => setConditions([...draft.conditions, newCondition('COUNT', opcode)])}>
              <Icon name="plus" size={14} />{t('rule.addCount')}</button>
          </div>
          <p className="small muted" style={{ margin: '8px 0 0' }}>{t('rule.treeHelp')}</p>
          {message && (
            <IcdTreeView message={message} extraHeaders={['']}
              renderCells={(node) => (
                <td>
                  {valueTypeOf(node) && (
                    <button className="btn ghost sm" onClick={() => addPredicate(node)} title={node.path}>
                      <Icon name="plus" size={13} />{t('rule.addPredicate')}</button>
                  )}
                </td>
              )} />
          )}
        </div>
      </div>
    </Modal>
  );
}

function ConditionEditor({ number, condition, tree, onChange, onRemove }: {
  number: number; condition: ConditionDraft; tree: IcdTree; onChange: (change: Partial<ConditionDraft>) => void; onRemove: () => void;
}) {
  const { t } = useI18n();
  const message = tree.messages.find((candidate) => candidate.opcode === condition.opcode);
  const nodes = message ? nodesByPath(message) : new Map<string, IcdTreeNode>();
  const setPredicate = (index: number, change: Partial<PredicateDraft>) =>
    onChange({ predicates: condition.predicates.map((predicate, i) => (i === index ? { ...predicate, ...change } : predicate)) });

  return (
    <div className="card flat rule-condition">
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <span className="badge neutral">{number}</span>
        <span>{t('rule.atLeast')}</span>
        <input className="input" type="number" min={1} style={{ width: 90 }} value={condition.minCount} aria-label={t('rule.minCount')}
          onChange={(e) => onChange({ minCount: e.target.value })} />
        <span>× <bdi className="strong">{message?.name ?? opcodeLabel(condition.opcode)}</bdi></span>
        {condition.kind === 'FIELDS' && <span className="muted">{t('rule.where')}</span>}
        <span className="grow" />
        <button className="btn ghost icon sm" title={t('common.remove')} onClick={onRemove}><Icon name="trash" /></button>
      </div>
      {condition.kind === 'FIELDS' && condition.predicates.length === 0 && <p className="small muted">{t('rule.noPredicates')}</p>}
      {condition.predicates.map((predicate, i) => {
        const node = nodes.get(predicate.path);
        return (
          <PredicateEditor key={i} predicate={predicate} node={node ?? null} first={i === 0}
            onChange={(change) => setPredicate(i, change)}
            onRemove={() => onChange({ predicates: condition.predicates.filter((_, j) => j !== i) })} />
        );
      })}
    </div>
  );
}

function PredicateEditor({ predicate, node, first, onChange, onRemove }: {
  predicate: PredicateDraft; node: IcdTreeNode | null; first: boolean; onChange: (change: Partial<PredicateDraft>) => void; onRemove: () => void;
}) {
  const { t } = useI18n();
  const type = node ? valueTypeOf(node) : null;
  const list = isList(predicate.op);

  return (
    <div className="row rule-predicate" style={{ gap: 8, flexWrap: 'wrap' }}>
      <span className="muted small" style={{ width: 36 }}>{first ? '' : t('rule.and')}</span>
      <span className="mono ltr">{predicate.path}</span>
      {!type && <span className="badge err">{t('rule.missingField')}</span>}
      {type && (
        <select className="select" style={{ width: 150 }} value={predicate.op} aria-label={t('rule.operator')}
          onChange={(e) => onChange({ op: e.target.value as Operator, names: predicate.names.slice(0, isList(e.target.value as Operator) ? undefined : 1) })}>
          {operatorsFor(type).map((op) => <option key={op} value={op}>{SYMBOLS[op]} {t(`op.${op}`)}</option>)}
        </select>
      )}
      {type === 'ENUM' && node && !list && (
        <select className="select" value={predicate.names[0] ?? ''} onChange={(e) => onChange({ names: [e.target.value] })}>
          {node.enumValues.map((value) => <option key={value.name} value={value.name}>{value.name} ({value.value})</option>)}
        </select>
      )}
      {type === 'ENUM' && node && list && (
        <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
          {node.enumValues.map((value) => {
            const on = predicate.names.includes(value.name);
            return (
              <button key={value.name} className={`chip selectable${on ? ' on' : ''}`}
                onClick={() => onChange({ names: on ? predicate.names.filter((name) => name !== value.name) : [...predicate.names, value.name] })}>
                {value.name}</button>
            );
          })}
        </div>
      )}
      {type === 'BOOL' && (
        <select className="select" value={String(predicate.bool)} onChange={(e) => onChange({ bool: e.target.value === 'true' })}>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      )}
      {(type === 'INTEGER' || type === 'FLOAT' || type === 'STRING') && (
        <input className="input ltr" style={{ width: list ? 240 : 140 }} value={predicate.text}
          inputMode={type === 'STRING' || list ? undefined : 'decimal'}
          placeholder={list ? t('rule.listPlaceholder') : predicate.op === 'BETWEEN' ? t('rule.low') : t('rule.value')}
          onChange={(e) => onChange({ text: e.target.value })} />
      )}
      {(type === 'INTEGER' || type === 'FLOAT') && predicate.op === 'BETWEEN' && (
        <>
          <span className="muted">…</span>
          <input className="input ltr" style={{ width: 140 }} value={predicate.text2} inputMode="decimal" placeholder={t('rule.high')}
            onChange={(e) => onChange({ text2: e.target.value })} />
        </>
      )}
      <button className="btn ghost icon sm" title={t('common.remove')} onClick={onRemove}><Icon name="x" /></button>
    </div>
  );
}
