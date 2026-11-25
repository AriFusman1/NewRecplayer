import { useState, type ReactNode } from 'react';
import type { IcdTreeMessage, IcdTreeNode } from '../../api/types';
import { useI18n } from '../../i18n';
import { flatten } from '../../util/timefix';

interface Props {
  message: IcdTreeMessage;
  /** Headers of the screen's own columns, after Field / Type / Size. */
  extraHeaders: ReactNode[];
  /** The screen's own cells for one field row (one <td> per extra header). */
  renderCells: (node: IcdTreeNode) => ReactNode;
  rowClassName?: (node: IcdTreeNode) => string | undefined;
}

/**
 * One message type's fields as a collapsible tree table: name, type and size, plus columns the
 * screen adds (time fix: base/fix/kind; rule editor: add a predicate). Structs, bitfields and the
 * header open and close.
 */
export function IcdTreeView({ message, extraHeaders, renderCells, rowClassName }: Props) {
  const { t, dir } = useI18n();
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

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
    <div className="table-wrap" style={{ marginTop: 10 }}>
      <table className="table tf-tree">
        <thead>
          <tr>
            <th>{t('tf.field')}</th><th>{t('tf.type')}</th><th>{t('tf.size')}</th>
            {extraHeaders.map((header, i) => <th key={i}>{header}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ node, depth }) => {
            const hasChildren = node.children.length > 0;
            const isCollapsed = collapsed.has(node.path);
            const size = node.kind === 'BIT' ? '—' : node.size === null ? t('tf.variable') : t('tf.bytes', { n: node.size });
            return (
              <tr key={node.path} className={rowClassName?.(node)}>
                <td>
                  <div className="row" style={{ gap: 6, paddingInlineStart: depth * 18 }} title={node.path}>
                    {hasChildren
                      ? <button className="btn ghost icon sm tf-toggle" onClick={() => toggleNode(node.path)}>
                          {isCollapsed ? (dir === 'rtl' ? '◂' : '▸') : '▾'}</button>
                      : <span className="tf-toggle" />}
                    <span className={node.kind === 'HEADER' || node.kind === 'STRUCT' ? 'strong' : undefined}><bdi>{node.name}</bdi></span>
                    {node.array !== null && <span className="small muted mono ltr">[{node.array}]</span>}
                  </div>
                </td>
                <td className="small"><span className="mono ltr">{node.type}</span></td>
                <td className="small">{node.array !== null && node.size !== null ? `${size} × ${node.array}` : size}</td>
                {renderCells(node)}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
