import { useState } from 'react';
import { api } from '../api/client';
import type { Mode, PcapRecord } from '../api/types';
import { useI18n } from '../i18n';
import { useFeedback } from '../state/feedback';
import { useNickname } from '../state/nickname';
import { loadPref, savePref } from '../util/prefs';
import { Modal } from './Modal';
import { openNowPlaying } from './NowPlaying';
import { TargetFields, targetValid } from './TargetFields';

export const DEFAULT_PORT = 5000;

/** Asks for the target, then starts a single replay on the server. */
export function PlayDialog({ record, mode, onClose }: { record: PcapRecord; mode: Mode; onClose: () => void }) {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { showError, toast } = useFeedback();
  const [host, setHost] = useState<string | null>(mode === 'TCP_CLIENT' ? null : loadPref('lastHost') ?? '');
  const [port, setPort] = useState(DEFAULT_PORT);
  const [busy, setBusy] = useState(false);
  const valid = targetValid(mode, host, port);

  const start = async () => {
    if (!valid) return;
    setBusy(true);
    try {
      if (host) savePref('lastHost', host.trim());
      await api.start([{ recordId: record.id, host: host?.trim() || null, port, sleepAfterMs: 0 }], false, nickname);
      toast('success', t('play.started', { name: record.name }));
      onClose();
      openNowPlaying();
    } catch (e) {
      showError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={t('play.title', { name: record.name })} icon="info" onClose={onClose}
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!valid || busy} onClick={start}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M7 5v14l11-7z" /></svg>{t('play.start')}
        </button>
      </>}>
      <p className="small muted">{t(`modeHint.${mode}`)}</p>
      <form onSubmit={(e) => { e.preventDefault(); void start(); }}>
        <TargetFields mode={mode} host={host} port={port} autoFocus onChange={(h, p) => { setHost(h); setPort(p); }} />
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
