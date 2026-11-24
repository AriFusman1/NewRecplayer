import { useState } from 'react';
import { api } from '../../api/client';
import type { Mode, PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { targetValid, toItemRequest } from '../../util/target';
import { rememberTargets, suggestTarget } from '../../util/targetMemory';
import { Icon } from '../common/Icon';
import { Modal } from '../common/Modal';
import { openNowPlaying } from './NowPlaying';
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

  const start = async () => {
    if (!valid) return;
    const item = toItemRequest({ recordId: record.id, host, port, sleepAfterMs: 0 });
    const started = await run(() => api.start([item], false, nickname), t('play.started', { name: record.name }));
    if (started) {
      rememberTargets([item]);
      onClose();
      openNowPlaying();
    }
  };

  return (
    <Modal title={t('play.title', { name: record.name })} icon="info" onClose={onClose}
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!valid || busy} onClick={start}>
          <Icon name="play" size={12} />{t('play.start')}
        </button>
      </>}>
      <p className="small muted">{t(`modeHint.${mode}`)}</p>
      <form onSubmit={(e) => { e.preventDefault(); void start(); }}>
        <TargetFields mode={mode} host={host} port={port} autoFocus onChange={(newHost, newPort) => { setHost(newHost); setPort(newPort); }} />
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
