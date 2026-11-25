import { useState } from 'react';
import { api } from '../../api/client';
import type { Mode, PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import { useRoom } from '../../state/live';
import { useNickname } from '../../state/nickname';
import { targetValid, toItemRequest } from '../../util/target';
import { rememberTargets, suggestTarget } from '../../util/targetMemory';
import { Icon } from '../common/Icon';
import { Modal } from '../common/Modal';
import { TargetFields } from './TargetFields';

/** Asks for the target, then starts a single replay on the server. */
export function PlayDialog({ record, mode, onClose }: { record: PcapRecord; mode: Mode; onClose: () => void }) {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { run, busy } = useAction();
  const [suggested] = useState(() => suggestTarget(record.id, mode));
  const [host, setHost] = useState<string | null>(suggested.host);
  const [port, setPort] = useState(suggested.port);
  const valid = targetValid(mode, host, port);
  /** The server's replay slots may fill up while the dialog is open. */
  const full = useRoom().blockedFor(1);

  const start = async () => {
    if (!valid || full) return;
    const item = toItemRequest({ recordId: record.id, host, port, sleepAfterMs: 0 });
    const started = await run(() => api.start([item], false, nickname), t('play.started', { name: record.name }));
    if (started) {
      rememberTargets([item]);
      onClose();
    }
  };

  return (
    <Modal title={t('play.title', { name: record.name })} icon="info" onClose={onClose}
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!valid || busy || full !== null} title={full ?? undefined} onClick={start}>
          <Icon name="play" size={12} />{t('play.start')}
        </button>
      </>}>
      <p className="small muted">{t(`modeHint.${mode}`)}</p>
      {full && <p className="small" role="status" style={{ color: 'var(--warning)' }}>{full}</p>}
      <form onSubmit={(e) => { e.preventDefault(); void start(); }}>
        <TargetFields mode={mode} host={host} port={port} autoFocus onChange={(newHost, newPort) => { setHost(newHost); setPort(newPort); }} />
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
