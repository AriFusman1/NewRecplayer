import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { ItemRequest, Session, SessionItem } from '../api/types';
import { useI18n } from '../i18n';
import { useCatalog } from '../state/catalog';
import { useFeedback } from '../state/feedback';
import { useLive } from '../state/live';
import { useNickname } from '../state/nickname';
import { moveItem } from '../state/playlist';
import { ModeBadge } from './Badges';
import { Icon } from './Icon';
import { LiveView, LogView } from './LiveView';
import { Confirm } from './Modal';
import { useDragReorder } from './useDragReorder';

const OPEN_EVENT = 'pcap-player:open-now-playing';

/** Expands the Now Playing panel (e.g. right after starting a replay). */
export function openNowPlaying() {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

/** Docked above the bottom navigation on every page; shows every running replay from everyone. */
export function NowPlaying() {
  const { t } = useI18n();
  const { sessions } = useLive();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const h = () => setOpen(true);
    window.addEventListener(OPEN_EVENT, h);
    return () => window.removeEventListener(OPEN_EVENT, h);
  }, []);
  useEffect(() => {
    if (sessions.length === 0) setOpen(false);
  }, [sessions.length]);

  const active = sessions.length > 0;
  return (
    <div className="now">
      <div className="now-bar" onClick={() => active && setOpen(!open)}>
        <div className={`eq${active ? '' : ' idle'}`}><span /><span /><span /></div>
        <strong>{t('now.title')}</strong>
        {active ? <span className="badge ok">{t('now.count', { n: sessions.length })}</span>
          : <span className="muted small">{t('now.empty')}</span>}
        <div className="now-strip">
          {!open && sessions.map((s) => <SessionPill key={s.id} s={s} />)}
        </div>
        {active && <Icon name={open ? 'chevronDown' : 'chevronUp'} />}
      </div>
      {open && active && (
        <div className="now-panel">
          <div className="now-panel-inner">
            {sessions.map((s) => <SessionCard key={s.id} s={s} />)}
          </div>
        </div>
      )}
    </div>
  );
}

function current(s: Session): SessionItem | undefined {
  return s.items[Math.max(0, s.currentIndex)];
}

function stateText(s: Session, t: ReturnType<typeof useI18n>['t']): string {
  const item = current(s);
  return t(`state.${s.state}`, { port: item?.port ?? '', seconds: Math.ceil(s.sleepRemainingMs / 1000) });
}

function SessionPill({ s }: { s: Session }) {
  const { t } = useI18n();
  const item = current(s);
  return (
    <span className="pill">
      <span className={`dot${s.state === 'PLAYING' ? ' pulse' : ''}`} style={{ color: s.state === 'PLAYING' ? 'var(--success)' : 'var(--warning)' }} />
      <span className="ellipsis">{item?.recordName}</span>
      <span className="muted ellipsis">{stateText(s, t)}</span>
    </span>
  );
}

function SessionCard({ s }: { s: Session }) {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { showError } = useFeedback();
  const [confirm, setConfirm] = useState(false);
  const item = current(s);
  if (!item) return null;
  const target = item.mode === 'TCP_CLIENT' ? `:${item.port}` : `${item.host}:${item.port}`;

  const stop = async () => {
    setConfirm(false);
    try {
      await api.stop(s.id, nickname);
    } catch (e) {
      showError(e);
    }
  };

  return (
    <div className="session-card">
      <div className="session-head">
        <div className="grow col" style={{ gap: 4 }}>
          <div className="row wrap">
            {s.playlist && <span className="badge neutral"><Icon name="list" size={13} />{t('now.itemOf', { n: s.currentIndex + 1, m: s.items.length })}</span>}
            <span className="title">{item.recordName}</span>
            <ModeBadge mode={item.mode} />
            <span className="chip mono ltr">{target}</span>
          </div>
          <div className="row wrap small muted">
            <span>{item.categoryName}</span>·<span>{t('now.startedBy', { name: s.startedBy })}</span>
          </div>
        </div>
        <span className={`badge ${s.state === 'PLAYING' ? 'ok' : 'warn'}`}>
          <span className={`dot${s.state === 'PLAYING' ? ' pulse' : ''}`} />{stateText(s, t)}
        </span>
        <button className="btn danger sm" onClick={() => setConfirm(true)}><Icon name="stop" size={12} />{t('common.stop')}</button>
      </div>

      {s.state === 'WAITING_FOR_CONNECTION' && (
        <div className="waiting"><span className="spinner" />{t('state.WAITING_FOR_CONNECTION', { port: item.port })}</div>
      )}
      {(s.state === 'PREPARING' || s.state === 'CONNECTING') && (
        <div className="row muted"><span className="spinner" />{stateText(s, t)}</div>
      )}
      {s.state === 'SLEEPING' && (
        <div className="row"><Icon name="clock" /><span className="sleep-ring ltr">{Math.ceil(s.sleepRemainingMs / 1000)}s</span>
          <span className="muted">{stateText(s, t)}</span></div>
      )}
      {s.progress && s.state !== 'SLEEPING' && <LiveView progress={s.progress} />}
      {s.playlist && <PlaylistEditor s={s} />}
      <LogView lines={s.log} />

      {confirm && (
        <Confirm title={s.playlist ? t('now.stopPlaylistConfirm') : t('now.stopConfirm')} body={item.recordName}
          confirmLabel={t('common.stop')} cancelLabel={t('common.cancel')} danger onConfirm={stop} onCancel={() => setConfirm(false)} />
      )}
    </div>
  );
}

/**
 * Items of a running playlist. Upcoming items can be reordered, removed, retargeted and their
 * sleeps changed by anyone; the current item is locked. Every change is sent to the server,
 * which broadcasts it to all viewers.
 */
function PlaylistEditor({ s }: { s: Session }) {
  const { t } = useI18n();
  const { catalog, recordById, categoryById } = useCatalog();
  const { showError } = useFeedback();
  const cur = s.currentIndex;
  const upcoming = s.items.slice(cur + 1);

  const send = async (items: ItemRequest[], currentSleep: number | null = null) => {
    try {
      await api.updateUpcoming(s.id, currentSleep, items);
    } catch (e) {
      showError(e);
    }
  };
  const asRequests = (list: SessionItem[]): ItemRequest[] =>
    list.map((i) => ({ recordId: i.recordId, host: i.host, port: i.port, sleepAfterMs: i.sleepAfterMs }));

  const { rowProps } = useDragReorder(
    (from, to) => void send(asRequests(moveItem(upcoming, from - cur - 1, to - cur - 1))),
    (i) => i > cur,
  );

  const patch = (idx: number, p: Partial<ItemRequest>) => {
    const reqs = asRequests(upcoming);
    reqs[idx - cur - 1] = { ...reqs[idx - cur - 1], ...p };
    void send(reqs);
  };

  const addRecord = (recordId: string) => {
    const r = recordById.get(recordId);
    if (!r) return;
    const mode = categoryById.get(r.categoryId)?.mode;
    const last = s.items[s.items.length - 1];
    void send([...asRequests(upcoming), {
      recordId, host: mode === 'TCP_CLIENT' ? null : last?.host ?? '', port: last?.port ?? 5000, sleepAfterMs: 0,
    }]);
  };

  return (
    <div className="field">
      <span className="label">{t('playlist.title')}</span>
      <div className="pl-items">
        {s.items.map((it, i) => {
          const editable = i > cur;
          const drag = rowProps(i);
          return (
            <div key={it.itemId}>
              <div {...drag} className={`pl-item ${i === cur ? 'current' : ''} ${i < cur ? 'done' : ''} ${drag.className}`}>
                <span className="handle">{editable ? <Icon name="grip" /> : null}</span>
                <span className="idx">{i + 1}</span>
                <div className="col" style={{ gap: 2, minWidth: 0 }}>
                  <div className="row"><span className="ellipsis" style={{ fontWeight: 600 }}>{it.recordName}</span><ModeBadge mode={it.mode} /></div>
                  {editable ? (
                    <EditableTarget item={it} onCommit={(host, port) => patch(i, { host, port })} />
                  ) : (
                    <span className="small muted mono ltr">{it.mode === 'TCP_CLIENT' ? `:${it.port}` : `${it.host}:${it.port}`}
                      {i === cur ? ` · ${t('now.locked')}` : ` · ${t(`status.${it.status}`)}`}</span>
                  )}
                </div>
                {editable
                  ? <button className="btn ghost icon sm" onClick={() => void send(asRequests(upcoming.filter((x) => x.itemId !== it.itemId)))}><Icon name="x" /></button>
                  : <span className={`badge ${it.status === 'FAILED' ? 'bad' : it.status === 'COMPLETED' ? 'ok' : 'neutral'}`}>{t(`status.${it.status}`)}</span>}
              </div>
              {i < s.items.length - 1 && i >= cur && (
                <div className="pl-sleep">
                  {t('playlist.sleep')}
                  <input key={`${it.itemId}-${it.sleepAfterMs}`} className="input mono" type="number" min={0} step={0.5}
                    defaultValue={it.sleepAfterMs / 1000}
                    onBlur={(e) => {
                      const ms = Math.max(0, Math.round(parseFloat(e.target.value || '0') * 1000));
                      if (ms === it.sleepAfterMs) return;
                      if (i === cur) void send(asRequests(upcoming), ms);
                      else patch(i, { sleepAfterMs: ms });
                    }} />
                  {t('common.seconds')}
                </div>
              )}
            </div>
          );
        })}
      </div>
      <select className="select" value="" onChange={(e) => e.target.value && addRecord(e.target.value)} style={{ maxWidth: 320 }}>
        <option value="">{t('now.addItem')}</option>
        {catalog.records.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
      </select>
    </div>
  );
}

/** Uncontrolled inputs so 5 Hz updates don't fight typing; committed on blur / Enter. */
function EditableTarget({ item, onCommit }: { item: SessionItem; onCommit: (host: string | null, port: number) => void }) {
  const commit = (host: string | null, port: number) => {
    if (host === item.host && port === item.port) return;
    if (!Number.isInteger(port) || port < 1 || port > 65535) return;
    onCommit(host, port);
  };
  const key = `${item.itemId}-${item.host}-${item.port}`;
  return (
    <div className="row" style={{ marginTop: 4 }}>
      {item.mode !== 'TCP_CLIENT' && (
        <input key={`h-${key}`} className="input mono" style={{ width: 140, height: 28, direction: 'ltr' }} defaultValue={item.host ?? ''}
          onBlur={(e) => commit(e.target.value.trim(), item.port)} onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()} />
      )}
      <input key={`p-${key}`} className="input mono" style={{ width: 84, height: 28 }} type="number" defaultValue={item.port}
        onBlur={(e) => commit(item.host, parseInt(e.target.value, 10))} onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()} />
    </div>
  );
}
