import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/client';
import type { ItemRequest, Session, SessionItem } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';
import { useLive } from '../../state/live';
import { useNickname } from '../../state/nickname';
import { moveItem } from '../../state/playlist';
import { DEFAULT_PORT, formatTarget, initialHost, portValid, secondsToMs, toItemRequest } from '../../util/target';
import { rememberTargets } from '../../util/targetMemory';
import { ModeBadge } from '../common/Badges';
import { Icon } from '../common/Icon';
import { LiveView, LogView } from './LiveView';
import { Confirm } from '../common/Modal';
import { SortableList, SortableRow, type SortableRowProps } from '../common/Sortable';

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
    const openPanel = () => setOpen(true);
    window.addEventListener(OPEN_EVENT, openPanel);
    return () => window.removeEventListener(OPEN_EVENT, openPanel);
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
          {!open && sessions.map((session) => <SessionPill key={session.id} session={session} />)}
        </div>
        {active && <Icon name={open ? 'chevronDown' : 'chevronUp'} />}
      </div>
      {open && active && (
        <div className="now-panel">
          <div className="now-panel-inner">
            {sessions.map((session) => <SessionCard key={session.id} session={session} />)}
          </div>
        </div>
      )}
    </div>
  );
}

function current(session: Session): SessionItem | undefined {
  return session.items[Math.max(0, session.currentIndex)];
}

function stateText(session: Session, t: ReturnType<typeof useI18n>['t']): string {
  const item = current(session);
  return t(`state.${session.state}`, { port: item?.port ?? '', seconds: Math.ceil(session.sleepRemainingMs / 1000) });
}

function SessionPill({ session }: { session: Session }) {
  const { t } = useI18n();
  const item = current(session);
  return (
    <span className="pill">
      <span className={`dot${session.state === 'PLAYING' ? ' pulse' : ''}`} style={{ color: session.state === 'PLAYING' ? 'var(--success)' : 'var(--warning)' }} />
      <span className="ellipsis">{item?.recordName}</span>
      <span className="muted ellipsis">{stateText(session, t)}</span>
    </span>
  );
}

function SessionCard({ session }: { session: Session }) {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { run } = useAction();
  const [confirm, setConfirm] = useState(false);
  const item = current(session);
  if (!item) return null;
  const target = formatTarget(item.mode, item.host, item.port);

  const stop = () => {
    setConfirm(false);
    void run(() => api.stop(session.id, nickname));
  };
  const seek = (positionUs: number) => void run(() => api.seek(session.id, positionUs, nickname));

  return (
    <div className="session-card">
      <div className="session-head">
        <div className="grow col" style={{ gap: 4 }}>
          <div className="row wrap">
            {session.playlist && <span className="badge neutral"><Icon name="list" size={13} />{t('now.itemOf', { n: session.currentIndex + 1, m: session.items.length })}</span>}
            <span className="title">{item.recordName}</span>
            <ModeBadge mode={item.mode} />
            <span className="chip mono ltr">{target}</span>
          </div>
          <div className="row wrap small muted">
            <span>{item.categoryName}</span>·<span>{t('now.startedBy', { name: session.startedBy })}</span>
          </div>
        </div>
        <span className={`badge ${session.state === 'PLAYING' ? 'ok' : 'warn'}`}>
          <span className={`dot${session.state === 'PLAYING' ? ' pulse' : ''}`} />{stateText(session, t)}
        </span>
        <button className="btn danger sm" onClick={() => setConfirm(true)}><Icon name="stop" size={12} />{t('common.stop')}</button>
      </div>

      {session.state === 'WAITING_FOR_CONNECTION' && (
        <div className="waiting"><span className="spinner" />{t('state.WAITING_FOR_CONNECTION', { port: item.port })}</div>
      )}
      {(session.state === 'PREPARING' || session.state === 'CONNECTING') && (
        <div className="row muted"><span className="spinner" />{stateText(session, t)}</div>
      )}
      {session.state === 'SLEEPING' && (
        <div className="row"><Icon name="clock" /><span className="sleep-ring ltr">{Math.ceil(session.sleepRemainingMs / 1000)}session</span>
          <span className="muted">{stateText(session, t)}</span></div>
      )}
      {session.progress && session.state !== 'SLEEPING' && <LiveView progress={session.progress} onSeek={session.state === 'PLAYING' ? seek : undefined} />}
      {session.playlist && <PlaylistEditor session={session} />}
      <LogView lines={session.log} />

      {confirm && (
        <Confirm title={session.playlist ? t('now.stopPlaylistConfirm') : t('now.stopConfirm')} body={item.recordName}
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
function PlaylistEditor({ session }: { session: Session }) {
  const { t } = useI18n();
  const { catalog, modeOf } = useCatalog();
  const { run } = useAction();
  const currentIndex = session.currentIndex;
  const upcoming = session.items.slice(currentIndex + 1);
  /** Position of item `i` (an index into all items) within the upcoming list. */
  const upcomingIndex = (i: number) => i - currentIndex - 1;
  // the session updates 5x per second; the record list only changes with the catalog
  const recordOptions = useMemo(() => catalog.records.map((record) => <option key={record.id} value={record.id}>{record.name}</option>), [catalog.records]);

  const send = (items: ItemRequest[], currentSleep: number | null = null) =>
    void run(() => api.updateUpcoming(session.id, currentSleep, items));
  const asRequests = (list: SessionItem[]): ItemRequest[] => list.map(toItemRequest);

  /** Only upcoming items move; a drop onto the current or a finished item is ignored. */
  const onMove = (from: number, to: number) => {
    if (from <= currentIndex || to <= currentIndex) return;
    send(asRequests(moveItem(upcoming, upcomingIndex(from), upcomingIndex(to))));
  };

  const patch = (i: number, p: Partial<ItemRequest>) => {
    const requests = asRequests(upcoming);
    requests[upcomingIndex(i)] = { ...requests[upcomingIndex(i)], ...p };
    send(requests);
  };
  const remove = (itemId: string) => send(asRequests(upcoming.filter((item) => item.itemId !== itemId)));

  /** Appends a record, aimed at the same target as the last item (the usual case). */
  const addRecord = (recordId: string) => {
    const mode = modeOf(recordId);
    if (!mode) return;
    const last = session.items[session.items.length - 1];
    send([...asRequests(upcoming), {
      recordId, host: initialHost(mode, last?.host ?? ''), port: last?.port ?? DEFAULT_PORT, sleepAfterMs: 0,
    }]);
  };

  const commitSleep = (i: number, it: SessionItem, text: string) => {
    const sleepMs = secondsToMs(text);
    if (sleepMs === it.sleepAfterMs) return;
    if (i === currentIndex) send(asRequests(upcoming), sleepMs);
    else patch(i, { sleepAfterMs: sleepMs });
  };

  return (
    <div className="field">
      <span className="label">{t('playlist.title')}</span>
      <div className="pl-items">
        <SortableList ids={session.items.map((item) => item.itemId)} onMove={onMove}>
          {session.items.map((item, i) => (
            <SortableRow key={item.itemId} id={item.itemId} disabled={i <= currentIndex}>
              {(sortable) => (
                <SessionItemRow item={item} index={i} currentIndex={currentIndex} hasNext={i < session.items.length - 1} sortable={sortable}
                  onRetarget={(host, port) => {
                    patch(i, { host, port });
                    rememberTargets([{ recordId: item.recordId, host, port, sleepAfterMs: item.sleepAfterMs }]);
                  }}
                  onRemove={() => remove(item.itemId)}
                  onSleep={(text) => commitSleep(i, item, text)} />
              )}
            </SortableRow>
          ))}
        </SortableList>
      </div>
      <select className="select" value="" onChange={(e) => e.target.value && addRecord(e.target.value)} style={{ maxWidth: 320 }}>
        <option value="">{t('now.addItem')}</option>
        {recordOptions}
      </select>
    </div>
  );
}

/**
 * One item of a running playlist. Finished items and the current one are read-only; upcoming
 * ones can be dragged, retargeted, removed, and have their pause changed.
 */
function SessionItemRow({ item, index, currentIndex, hasNext, sortable, onRetarget, onRemove, onSleep }: {
  item: SessionItem; index: number; currentIndex: number; hasNext: boolean; sortable: SortableRowProps;
  onRetarget: (host: string | null, port: number) => void; onRemove: () => void; onSleep: (text: string) => void;
}) {
  const { t } = useI18n();
  const editable = index > currentIndex;
  const state = index === currentIndex ? ' current' : index < currentIndex ? ' done' : '';
  return (
    <div {...sortable.rowProps}>
      <div className={`pl-item${state}${sortable.dragging ? ' dragging' : ''}`}>
        <span className="handle" {...sortable.handleProps}>{editable ? <Icon name="grip" /> : null}</span>
        <span className="idx">{index + 1}</span>
        <div className="col tight">
          <div className="row"><span className="ellipsis strong">{item.recordName}</span><ModeBadge mode={item.mode} /></div>
          {editable ? <EditableTarget item={item} onCommit={onRetarget} /> : (
            <span className="small muted mono ltr">{formatTarget(item.mode, item.host, item.port)}
              {index === currentIndex ? ` · ${t('now.locked')}` : ` · ${t(`status.${item.status}`)}`}</span>
          )}
        </div>
        {editable
          ? <button className="btn ghost icon sm" onClick={onRemove}><Icon name="x" /></button>
          : <span className={`badge ${statusBadge(item.status)}`}>{t(`status.${item.status}`)}</span>}
      </div>
      {hasNext && index >= currentIndex && (
        <div className="pl-sleep">
          {t('playlist.sleep')}
          <input key={`${item.itemId}-${item.sleepAfterMs}`} className="input mono" type="number" min={0} step={0.5}
            defaultValue={item.sleepAfterMs / 1000} onBlur={(e) => onSleep(e.target.value)} />
          {t('common.seconds')}
        </div>
      )}
    </div>
  );
}

function statusBadge(status: SessionItem['status']): string {
  if (status === 'FAILED') return 'bad';
  if (status === 'COMPLETED') return 'ok';
  return 'neutral';
}

/** Uncontrolled inputs so 5 Hz updates don't fight typing; committed on blur / Enter. */
function EditableTarget({ item, onCommit }: { item: SessionItem; onCommit: (host: string | null, port: number) => void }) {
  const commit = (host: string | null, port: number) => {
    if (host === item.host && port === item.port) return;
    if (!portValid(port)) return;
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
