import type { Mode, Problem } from '../api/types';
import { useI18n } from '../i18n';
import { formatUs } from '../util/time';
import { Icon } from './Icon';

export function ModeBadge({ mode }: { mode: Mode }) {
  const { t } = useI18n();
  return <span className={`badge mode-${mode}`}>{t(`mode.${mode}`)}</span>;
}

interface GapDetail {
  tsUs: number;
  bytes: number;
  connection: string;
}

/** Warnings / errors from the server (code + params), translated. TCP gaps also list where they are. */
export function Warnings({ items, kind = 'warn' }: { items: Problem[]; kind?: 'warn' | 'err' }) {
  const { tc } = useI18n();
  if (!items.length) return null;
  return (
    <div className="alerts">
      {items.map((w, i) => (
        <div key={i} className={`alert${kind === 'err' ? ' error' : ''}`}>
          <Icon name="alert" />
          <div className="grow">
            {tc(kind, w.code, w.params)}
            {w.code === 'TCP_GAPS' && Array.isArray(w.params.details) && <GapDetails details={w.params.details as GapDetail[]} />}
          </div>
        </div>
      ))}
    </div>
  );
}

function GapDetails({ details }: { details: GapDetail[] }) {
  return (
    <ul className="small" style={{ margin: '6px 0 0', paddingInlineStart: 18 }}>
      {details.slice(0, 5).map((d, i) => (
        <li key={i}>
          <span className="mono ltr">{formatUs(d.tsUs)}</span> · <span className="mono ltr">{d.connection}</span> · {d.bytes.toLocaleString('en-US')} B
        </li>
      ))}
      {details.length > 5 && <li>…</li>}
    </ul>
  );
}
