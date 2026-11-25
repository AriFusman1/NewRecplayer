import { useState } from 'react';
import { TIME_KINDS, type IcdTreeMessage, type IcdTreeNode, type MessageTimeFix, type TimeFieldRef, type TimeKind } from '../../api/types';
import { useI18n } from '../../i18n';
import { defaultKind, missingBase, opcodeLabel, roleOf } from '../../util/timefix';
import { IcdTreeView } from '../icd/IcdTreeView';
import { Warnings } from '../common/Badges';
import { Icon } from '../common/Icon';

interface Props {
  message: IcdTreeMessage;
  /** The message's entry in the draft, if it has one. */
  fix: MessageTimeFix | undefined;
  onBase: (ref: TimeFieldRef) => void;
  onToggle: (ref: TimeFieldRef, on: boolean) => void;
  onKind: (path: string, kind: TimeKind) => void;
  onClear: () => void;
}

/**
 * One message type: a summary line that opens the message's field tree. Number fields get a radio
 * (the base field, one per message), a checkbox (a field to fix) and, once chosen, a time-kind dropdown.
 */
export function MessageTimeFixCard({ message, fix, onBase, onToggle, onKind, onClear }: Props) {
  const { t, dir } = useI18n();
  const [open, setOpen] = useState(!!fix?.base);
  const configured = !!fix && (!!fix.base || fix.fields.length > 0);
  return (
    <section className="card tf-message">
      <div className="tf-message-head" role="button" tabIndex={0} onClick={() => setOpen(!open)}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && setOpen(!open)}>
        <span className="tf-chevron">{open ? '▾' : dir === 'rtl' ? '◂' : '▸'}</span>
        <span className="strong"><bdi>{message.name}</bdi></span>
        <span className="small muted mono ltr">{opcodeLabel(message.opcode)}</span>
        <span className="small muted">{message.size === null ? t('tf.variable') : t('tf.bytes', { n: message.size })}</span>
        <span className="grow" />
        <span className={`badge ${configured ? 'ok' : 'neutral'}`}>
          {fix?.base
            ? t('tf.summary', { base: fix.base.path, kind: t(`kind.${fix.base.kind}`), n: fix.fields.length })
            : configured ? t('tf.summaryNoBase', { n: fix?.fields.length ?? 0 }) : t('tf.summaryNone')}
        </span>
        {configured && (
          <button className="btn ghost icon sm" title={t('tf.clear')} onClick={(e) => { e.stopPropagation(); onClear(); }}>
            <Icon name="x" />
          </button>
        )}
      </div>
      {missingBase(fix) && <Warnings kind="err" items={[{ code: 'TIMEFIX_NO_BASE', params: { message: message.name } }]} />}
      {open && (
        <IcdTreeView message={message} extraHeaders={[t('tf.base'), t('tf.fix'), t('tf.kind')]}
          rowClassName={(node) => (roleOf(fix, node.path).role ? 'tf-chosen' : undefined)}
          renderCells={(node) => <FieldCells node={node} opcode={message.opcode} fix={fix} onBase={onBase} onToggle={onToggle} onKind={onKind} />} />
      )}
    </section>
  );
}

/** The time-fix columns of one field row: base radio, fix checkbox, time kind. */
function FieldCells({ node, opcode, fix, onBase, onToggle, onKind }: {
  node: IcdTreeNode; opcode: number; fix: MessageTimeFix | undefined;
  onBase: (ref: TimeFieldRef) => void; onToggle: (ref: TimeFieldRef, on: boolean) => void; onKind: (path: string, kind: TimeKind) => void;
}) {
  const { t } = useI18n();
  const { role, kind } = roleOf(fix, node.path);

  return (
    <>
      <td>
        {node.numeric && (
          <input type="radio" name={`base-${opcode}`} checked={role === 'base'} disabled={node.inArray}
            title={node.inArray ? t('tf.inArrayBase') : t('tf.base')} aria-label={t('tf.base')}
            onChange={() => onBase({ path: node.path, kind: kind ?? defaultKind(node) })} />
        )}
      </td>
      <td>
        {node.numeric && (
          <input type="checkbox" checked={role === 'fix'} disabled={role === 'base'} aria-label={t('tf.fix')}
            title={node.inArray ? t('tf.inArrayFix') : t('tf.fix')}
            onChange={(e) => onToggle({ path: node.path, kind: kind ?? defaultKind(node) }, e.target.checked)} />
        )}
      </td>
      <td>
        {role && kind && (
          <select className="select" style={{ width: 130 }} value={kind} aria-label={t('tf.kind')}
            onChange={(e) => onKind(node.path, e.target.value as TimeKind)}>
            {TIME_KINDS.map((option) => <option key={option} value={option}>{t(`kind.${option}`)}</option>)}
          </select>
        )}
      </td>
    </>
  );
}
