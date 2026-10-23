import { useState } from 'react';
import { api } from '../api/client';
import { useI18n } from '../i18n';
import { useCatalog } from '../state/catalog';
import { useFeedback } from '../state/feedback';
import { useNickname } from '../state/nickname';
import { usePlaylist } from '../state/playlist';
import { ModeBadge } from './Badges';
import { Icon } from './Icon';
import { openNowPlaying } from './NowPlaying';
import { TargetFields, targetValid } from './TargetFields';
import { useDragReorder } from './useDragReorder';

/** Side drawer for building a playlist (kept in localStorage, not on the server). */
export function PlaylistDrawer({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const { items, remove, move, update, clear } = usePlaylist();
  const { recordById, categoryById } = useCatalog();
  const { nickname } = useNickname();
  const { showError, toast } = useFeedback();
  const [busy, setBusy] = useState(false);
  const { rowProps } = useDragReorder(move);

  const resolved = items.map((it) => {
    const r = recordById.get(it.recordId);
    return { it, record: r, mode: r ? categoryById.get(r.categoryId)?.mode : undefined };
  });
  const allValid = resolved.length > 0 && resolved.every((x) => x.record && x.mode && targetValid(x.mode, x.it.host, x.it.port));

  const play = async () => {
    setBusy(true);
    try {
      await api.start(items.map(({ recordId, host, port, sleepAfterMs }) => ({ recordId, host: host?.trim() || null, port, sleepAfterMs })), true, nickname);
      toast('success', t('playlist.play'));
      onClose();
      openNowPlaying();
    } catch (e) {
      showError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <aside className="drawer">
        <div className="drawer-head">
          <div className="row"><Icon name="list" size={18} /><h2>{t('playlist.title')}</h2><span className="badge neutral">{items.length}</span></div>
          <button className="btn ghost icon sm" onClick={onClose}><Icon name="x" /></button>
        </div>
        <div className="drawer-body">
          {items.length === 0 && <div className="empty"><Icon name="list" size={28} />{t('playlist.empty')}</div>}
          {items.length > 1 && <span className="small muted">{t('playlist.dragHint')}</span>}
          {resolved.map(({ it, record, mode }, i) => {
            const drag = rowProps(i);
            return (
              <div key={it.key}>
                <div {...drag} className={`pl-item ${drag.className}`} style={{ gridTemplateColumns: '20px 28px minmax(0,1fr) auto', alignItems: 'start' }}>
                  <span className="handle" style={{ paddingTop: 4 }}><Icon name="grip" /></span>
                  <span className="idx">{i + 1}</span>
                  <div className="col" style={{ gap: 4, minWidth: 0 }}>
                    {record && mode ? (
                      <>
                        <div className="row"><span className="ellipsis" style={{ fontWeight: 600 }}>{record.name}</span><ModeBadge mode={mode} /></div>
                        <TargetFields compact mode={mode} host={it.host} port={it.port} onChange={(host, port) => update(it.key, { host, port })} />
                      </>
                    ) : <span className="muted">{t('playlist.missing')}</span>}
                  </div>
                  <button className="btn ghost icon sm" onClick={() => remove(it.key)}><Icon name="x" /></button>
                </div>
                {i < items.length - 1 && (
                  <div className="pl-sleep">
                    {t('playlist.sleep')}
                    <input className="input mono" type="number" min={0} step={0.5} value={it.sleepAfterMs / 1000}
                      onChange={(e) => update(it.key, { sleepAfterMs: Math.max(0, Math.round(parseFloat(e.target.value || '0') * 1000)) })} />
                    {t('common.seconds')}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <div className="drawer-foot">
          <button className="btn ghost" disabled={!items.length} onClick={clear}>{t('playlist.clear')}</button>
          <div className="grow" />
          <button className="btn primary" disabled={!allValid || busy} onClick={play}><Icon name="play" size={12} />{t('playlist.play')}</button>
        </div>
      </aside>
    </>
  );
}
