import { useId, useMemo } from 'react';
import type { Mode } from '../../api/types';
import { useI18n } from '../../i18n';
import { DEFAULT_PORT } from '../../util/target';
import { recentHosts } from '../../util/targetMemory';

interface Props {
  mode: Mode;
  host: string | null;
  port: number;
  onChange: (host: string | null, port: number) => void;
  compact?: boolean;
  autoFocus?: boolean;
}

/** Target IP + port (TCP/UDP server modes) or listen port (TCP client mode). */
export function TargetFields({ mode, host, port, onChange, compact, autoFocus }: Props) {
  const { t } = useI18n();
  const listId = useId();
  const hosts = useMemo(() => recentHosts(), []);
  const portInput = (
    <input className="input mono" style={{ width: compact ? 90 : undefined }} type="number" min={1} max={65535}
      value={Number.isFinite(port) && port > 0 ? port : ''} placeholder={String(DEFAULT_PORT)}
      autoFocus={autoFocus && mode === 'TCP_CLIENT'}
      onChange={(e) => onChange(host, parseInt(e.target.value, 10))} />
  );
  if (mode === 'TCP_CLIENT') {
    return compact ? <div className="row"><span className="small muted">{t('play.listenPort')}</span>{portInput}</div> : (
      <div className="field"><label>{t('play.listenPort')}</label>{portInput}<span className="hint">{t('play.listenHint')}</span></div>
    );
  }
  // recently used IPs are offered as suggestions (a native datalist: type to filter, or pick)
  const hostInput = (
    <>
      <input className="input mono" style={{ direction: 'ltr', width: compact ? 150 : undefined }} value={host ?? ''}
        placeholder="10.0.0.5" autoFocus={autoFocus} list={listId} onChange={(e) => onChange(e.target.value, port)} />
      <datalist id={listId}>{hosts.map((recentHost) => <option key={recentHost} value={recentHost} />)}</datalist>
    </>
  );
  if (compact) return <div className="row">{hostInput}<span className="muted">:</span>{portInput}</div>;
  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="grid-2" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <div className="field"><label>{t('play.targetIp')}</label>{hostInput}</div>
        <div className="field"><label>{t('play.targetPort')}</label>{portInput}</div>
      </div>
      <span className="hint muted small">{t('play.connectHint')}</span>
    </div>
  );
}
