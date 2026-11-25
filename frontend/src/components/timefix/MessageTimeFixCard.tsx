import { useState } from 'react';
import { TIME_KINDS, type IcdTreeMessage, type IcdTreeNode, type MessageTimeFix, type TimeFieldRef, type TimeKind } from '../../api/types';
import { useI18n } from '../../i18n';
import { defaultKind, flatten, missingBase, opcodeLabel, roleOf } from '../../util/timefix';
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
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const configured = !!fix && (!!fix.base || fix.fields.length > 0);

  const toggleNode = (path: string) => setCollapsed((current) => {
    const next = new Set(current);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    return next;
  });
  // a node is shown unless one of its ancestors is collapsed
  const rows = flatten(message).filter(({ node }) =>
    ![...collapsed].some((path) => node.path !== path && (node.path.startsWith(`${path}.`) || node.path.startsWith(`${path}[`))));

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
        <div className="table-wrap" style={{ marginTop: 10 }}>
          <table className="table tf-tree">
            <thead>
              <tr>
                <th>{t('tf.field')}</th><th>{t('tf.type')}</th><th>{t('tf.size')}</th>
                <th>{t('tf.base')}</th><th>{t('tf.fix')}</th><th>{t('tf.kind')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ node, depth }) => (
                <FieldRow key={node.path} node={node} depth={depth} opcode={message.opcode} fix={fix}
                  collapsed={collapsed.has(node.path)} onToggleNode={() => toggleNode(node.path)}
                  onBase={onBase} onToggle={onToggle} onKind={onKind} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function FieldRow({ node, depth, opcode, fix, collapsed, onToggleNode, onBase, onToggle, onKind }: {
  node: IcdTreeNode; depth: number; opcode: number; fix: MessageTimeFix | undefined; collapsed: boolean; onToggleNode: () => void;
  onBase: (ref: TimeFieldRef) => void; onToggle: (ref: TimeFieldRef, on: boolean) => void; onKind: (path: string, kind: TimeKind) => void;
}) {
  const { t, dir } = useI18n();
  const { role, kind } = roleOf(fix, node.path);
  const hasChildren = node.children.length > 0;
  const size = node.kind === 'BIT' ? '—' : node.size === null ? t('tf.variable') : t('tf.bytes', { n: node.size });

  return (
    <tr className={role ? 'tf-chosen' : undefined}>
      <td>
        <div className="row" style={{ gap: 6, paddingInlineStart: depth * 18 }} title={node.path}>
          {hasChildren
            ? <button className="btn ghost icon sm tf-toggle" onClick={onToggleNode}>{collapsed ? (dir === 'rtl' ? '◂' : '▸') : '▾'}</button>
            : <span className="tf-toggle" />}
          <span className={node.kind === 'HEADER' || node.kind === 'STRUCT' ? 'strong' : undefined}><bdi>{node.name}</bdi></span>
          {node.array !== null && <span className="small muted mono ltr">[{node.array}]</span>}
        </div>
      </td>
      <td className="small"><span className="mono ltr">{node.type}</span></td>
      <td className="small">{node.array !== null && node.size !== null ? `${size} × ${node.array}` : size}</td>
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
    </tr>
  );
}
