import { useMemo, useState } from 'react';
import { ApiError, api } from '../../api/client';
import type { ItemRequest, Mode, PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useFeedback } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { targetKey, targetValid, toItemRequest } from '../../util/target';
import { rememberTargets, suggestTarget } from '../../util/targetMemory';
import { ModeBadge } from '../common/Badges';
import { Icon } from '../common/Icon';
import { Modal } from '../common/Modal';
import { openNowPlaying } from './NowPlaying';
import { TargetFields } from './TargetFields';

interface Entry {
  record: PcapRecord;
  mode: Mode;
  host: string | null;
  port: number;
}

/**
 * Asks for a target per record (the same fields as the single Play dialog), then starts them all
 * at once. The server starts all of them or none.
 */
export function PlayTogetherDialog({ records, onClose, onStarted }: {
  records: PcapRecord[]; onClose: () => void; onStarted: () => void;
}) {
  const { t, tc } = useI18n();
  const { categoryById } = useCatalog();
  const { nickname } = useNickname();
  const { popup, toast } = useFeedback();
  const [busy, setBusy] = useState(false);
  const [entries, setEntries] = useState<Entry[]>(() => records.flatMap((record) => {
    const mode = categoryById.get(record.categoryId)?.mode;
    return mode ? [{ record, mode, ...suggestTarget(record.id, mode) }] : [];
  }));
  /** Name of the record the server refused to start, highlighted until its target changes. */
  const [refused, setRefused] = useState<string | null>(null);

  // two records aimed at the same target can't play at the same time (only complete targets count,
  // so rows not filled in yet aren't flagged)
  const duplicates = useMemo(() => {
    const complete = entries.filter((entry) => targetValid(entry.mode, entry.host, entry.port));
    const counts = new Map<string, number>();
    complete.forEach((entry) => {
      const key = targetKey(entry.mode, entry.host, entry.port);
      counts.set(key, (counts.get(key) ?? 0) + 1);
    });
    return new Set(complete.filter((entry) => (counts.get(targetKey(entry.mode, entry.host, entry.port)) ?? 0) > 1)
      .map((entry) => entry.record.id));
  }, [entries]);
  const allValid = entries.length > 0 && duplicates.size === 0
    && entries.every((entry) => targetValid(entry.mode, entry.host, entry.port));

  const update = (recordId: string, host: string | null, port: number) => {
    setEntries((list) => list.map((entry) => (entry.record.id === recordId ? { ...entry, host, port } : entry)));
    setRefused(null);
  };

  const start = async () => {
    if (!allValid || busy) return;
    const items: ItemRequest[] = entries.map((entry) =>
      toItemRequest({ recordId: entry.record.id, host: entry.host, port: entry.port, sleepAfterMs: 0 }));
    setBusy(true);
    try {
      await api.startGroup(items, nickname);
      rememberTargets(items);
      toast('success', t('together.started', { n: items.length }));
      onStarted();
      onClose();
      openNowPlaying();
    } catch (e) {
      // the server names the record that could not start; nothing was started
      const record = e instanceof ApiError ? (e.params.record as string | undefined) : undefined;
      setRefused(record ?? null);
      const text = e instanceof ApiError ? tc('err', e.code, { ...e.params, message: e.message }, e.message) : String(e);
      popup(t('err.title'), record ? `${record}: ${text}` : text);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={t('together.title', { n: entries.length })} icon="info" onClose={onClose} wide
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!allValid || busy} onClick={start}>
          <Icon name="play" size={12} />{t('together.start', { n: entries.length })}
        </button>
      </>}>
      <p className="small muted">{t('together.hint')}</p>
      <form className="together-list" onSubmit={(e) => { e.preventDefault(); void start(); }}>
        {entries.map((entry, index) => {
          const duplicate = duplicates.has(entry.record.id);
          return (
            <section key={entry.record.id} className={`together-item${duplicate || refused === entry.record.name ? ' invalid' : ''}`}>
              <div className="row wrap">
                <span className="idx">{index + 1}</span>
                <strong className="grow ellipsis">{entry.record.name}</strong>
                <ModeBadge mode={entry.mode} />
              </div>
              <span className="small muted">{t(`modeHint.${entry.mode}`)}</span>
              <TargetFields mode={entry.mode} host={entry.host} port={entry.port} autoFocus={index === 0}
                onChange={(host, port) => update(entry.record.id, host, port)} />
              {duplicate && <span className="error-text">{t('together.duplicate')}</span>}
            </section>
          );
        })}
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
