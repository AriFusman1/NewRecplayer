import { type KeyboardEvent, useEffect, useMemo, useState } from 'react';
import { api } from '../../api/client';
import type { ItemRequest, Session, SessionGroup, SessionItem } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';
import { useLive } from '../../state/live';
import { useNickname } from '../../state/nickname';
import { moveItem } from '../../state/playlist';
import { DEFAULT_PORT, formatTarget, initialHost, msToSeconds, portValid, secondsLeft, secondsToMs, toItemRequest } from '../../util/target';
import { rememberTargets } from '../../util/targetMemory';
import { formatDuration, formatNumber } from '../../util/time';
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
  const { sessions, usage } = useLive();
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
  const entries = useMemo(() => groupSessions(sessions), [sessions]);
  return (
    <div className="now">
      <div className="now-bar" onClick={() => active && setOpen(!open)}>
        <div className={`eq${active ? '' : ' idle'}`}><span /><span /><span /></div>
        <strong>{t('now.title')}</strong>
        {usage.max > 0 && (
          <span className={`badge ${usage.active >= usage.max ? 'warn' : active ? 'ok' : 'neutral'}`} title={t('usage.hint')}>
            {t('usage.inUse', { active: usage.active, max: usage.max })}
          </span>
        )}
        {!active && <span className="muted small">{t('now.empty')}</span>}
        <div className="now-strip">
          {!open && entries.map((entry) => (entry.group
            ? <GroupPill key={entry.group.id} sessions={entry.sessions} />
            : <SessionPill key={entry.sessions[0].id} session={entry.sessions[0]} />))}
        </div>
        {active && <Icon name={open ? 'chevronDown' : 'chevronUp'} />}
      </div>
      {open && active && (
        <div className="now-panel">
          <div className="now-panel-inner">
            {entries.map((entry) => (entry.group
              ? <GroupCard key={entry.group.id} size={entry.group.size} sessions={entry.sessions} />
              : <SessionCard key={entry.sessions[0].id} session={entry.sessions[0]} />))}
          </div>
        </div>
      )}
    </div>
  );
}

/** A card in the panel: one session, or the sessions started together (in start order). */
interface Entry {
  group: SessionGroup | null;
  sessions: Session[];
}

function groupSessions(sessions: Session[]): Entry[] {
  const entries: Entry[] = [];
  const byGroup = new Map<string, Entry>();
  for (const session of sessions) {
    if (!session.group) {
      entries.push({ group: null, sessions: [session] });
      continue;
    }
    let entry = byGroup.get(session.group.id);
    if (!entry) {
      entry = { group: session.group, sessions: [] };
      byGroup.set(session.group.id, entry);
      entries.push(entry);
    }
    entry.sessions.push(session);
  }
  return entries;
}

function current(session: Session): SessionItem | undefined {
  return session.items[Math.max(0, session.currentIndex)];
}

function stateText(session: Session, t: ReturnType<typeof useI18n>['t']): string {
  const item = current(session);
  return t(`state.${session.state}`, { port: item?.port ?? '', seconds: secondsLeft(session.sleepRemainingMs) });
}

function isPlaying(session: Session): boolean {
  return session.state === 'PLAYING';
}

/** Commits an uncontrolled input on Enter by blurring it (its onBlur does the work). */
function blurOnEnter(event: KeyboardEvent<HTMLInputElement>) {
  if (event.key === 'Enter') event.currentTarget.blur();
}

function SessionPill({ session }: { session: Session }) {
  const { t } = useI18n();
  const item = current(session);
  const playing = isPlaying(session);
  return (
    <span className="pill">
      <span className={`dot${playing ? ' pulse' : ''}`} style={{ color: playing ? 'var(--success)' : 'var(--warning)' }} />
      <span className="ellipsis">{item?.recordName}</span>
      <span className="muted ellipsis">{stateText(session, t)}</span>
    </span>
  );
}

function GroupPill({ sessions }: { sessions: Session[] }) {
  const { t } = useI18n();
  const anyPlaying = sessions.some(isPlaying);
  return (
    <span className="pill">
      <span className={`dot${anyPlaying ? ' pulse' : ''}`} style={{ color: anyPlaying ? 'var(--success)' : 'var(--warning)' }} />
      <Icon name="layers" size={13} />
      <span className="ellipsis">{t('together.pill', { n: sessions.length })}</span>
    </span>
  );
}

const ALL_TAB = 'all';

/**
 * Replays started together: an "All" tab with every member's progress side by side, plus one tab
 * per member with its full live view. A member that has finished leaves the group's list.
 */
function GroupCard({ size, sessions }: { size: number; sessions: Session[] }) {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { run } = useAction();
  const [tab, setTab] = useState(ALL_TAB);
  const [confirm, setConfirm] = useState(false);
  const shown = sessions.find((session) => session.id === tab);
  const activeTab = shown ? tab : ALL_TAB; // the chosen member may have finished
  const group = sessions[0].group; // the latest counts, as sent with every member
  const waitingForClients = !!group && !group.started && group.tcpClients > 0;

  const stopAll = () => {
    setConfirm(false);
    void run(() => Promise.all(sessions.map((session) => api.stop(session.id, nickname))));
  };

  return (
    <div className="session-card group-card">
      <div className="session-head">
        <div className="grow col" style={{ gap: 4 }}>
          <div className="row wrap">
            <span className="badge neutral"><Icon name="layers" size={13} />{t('together.card')}</span>
            <span className="title">{t('together.running', { n: sessions.length, m: size })}</span>
          </div>
          <div className="row wrap small muted"><span>{t('now.startedBy', { name: sessions[0].startedBy })}</span></div>
        </div>
        <button className="btn danger sm" onClick={() => setConfirm(true)}><Icon name="stop" size={12} />{t('together.stopAll')}</button>
      </div>

      {waitingForClients && (
        <div className="waiting">
          <span className="spinner" />
          {t('together.waitingClients', { n: group.connectedClients, m: group.tcpClients })}
        </div>
      )}

      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={activeTab === ALL_TAB} className={`tab${activeTab === ALL_TAB ? ' active' : ''}`}
          onClick={() => setTab(ALL_TAB)}>{t('together.all')}</button>
        {sessions.map((session) => {
          const item = current(session);
          return (
            <button key={session.id} role="tab" aria-selected={activeTab === session.id}
              className={`tab${activeTab === session.id ? ' active' : ''}`} onClick={() => setTab(session.id)}>
              <span className={`dot${isPlaying(session) ? ' pulse' : ''}`}
                style={{ color: isPlaying(session) ? 'var(--success)' : 'var(--warning)' }} />
              <span className="ellipsis">{item?.recordName}</span>
            </button>
          );
        })}
      </div>

      {activeTab === ALL_TAB
        ? <div className="group-rows">{sessions.map((session) => <GroupRow key={session.id} session={session} onOpen={() => setTab(session.id)} />)}</div>
        : shown && <SessionCard session={shown} embedded />}

      {confirm && (
        <Confirm title={t('together.stopAllConfirm')} body={sessions.map((session) => current(session)?.recordName).join('\n')}
          confirmLabel={t('together.stopAll')} cancelLabel={t('common.cancel')} danger onConfirm={stopAll} onCancel={() => setConfirm(false)} />
      )}
    </div>
  );
}

/** One member in the "All" tab: name, target, state and progress. Click to open its tab. */
function GroupRow({ session, onOpen }: { session: Session; onOpen: () => void }) {
  const { t } = useI18n();
  const item = current(session);
  if (!item) return null;
  const progress = session.progress;
  const percent = progress && progress.durationUs > 0 ? Math.min(100, (progress.positionUs / progress.durationUs) * 100) : 0;
  const playing = isPlaying(session);
  return (
    <button className="group-row" onClick={onOpen}>
      <div className="col" style={{ gap: 4, minWidth: 0 }}>
        <div className="row"><strong className="ellipsis">{item.recordName}</strong><ModeBadge mode={item.mode} /></div>
        <span className="small muted mono ltr">{formatTarget(item.mode, item.host, item.port)}</span>
      </div>
      <div className="col" style={{ gap: 6, minWidth: 0 }}>
        {playing && progress ? (
          <>
            <div className="progress"><div style={{ width: `${percent}%` }} /></div>
            <div className="row between small muted">
              <span className="ltr">{formatDuration(progress.positionUs)} / {formatDuration(progress.durationUs)}</span>
              <span className="ltr">{formatNumber(progress.sentMessages)} / {formatNumber(progress.totalMessages)}</span>
            </div>
          </>
        ) : (
          clientConnected(session) ? (
            <span className="badge ok" style={{ alignSelf: 'flex-start' }}><Icon name="check" size={12} />{t('together.connected')}</span>
          ) : (
            <span className={`badge ${playing ? 'ok' : 'warn'}`} style={{ alignSelf: 'flex-start' }}>
              {session.state !== 'PLAYING' && <span className="spinner" style={{ width: 11, height: 11 }} />}{stateText(session, t)}
            </span>
          )
        )}
      </div>
    </button>
  );
}

/** A TCP client in a group that is waiting: its service is connected, it waits for the others. */
function clientConnected(session: Session): boolean {
  return current(session)?.mode === 'TCP_CLIENT' && session.state === 'WAITING_FOR_GROUP';
}

function SessionCard({ session, embedded = false }: { session: Session; embedded?: boolean }) {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { run } = useAction();
  const [confirm, setConfirm] = useState(false);
  const item = current(session);
  if (!item) return null;
  const target = formatTarget(item.mode, item.host, item.port);
  const playing = isPlaying(session);

  const stop = () => {
    setConfirm(false);
    void run(() => api.stop(session.id, nickname));
  };
  const seek = (positionUs: number) => void run(() => api.seek(session.id, positionUs, nickname));

  return (
    <div className={embedded ? 'session-card embedded' : 'session-card'}>
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
        <span className={`badge ${playing ? 'ok' : 'warn'}`}>
          <span className={`dot${playing ? ' pulse' : ''}`} />{stateText(session, t)}
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
        <div className="row"><Icon name="clock" /><span className="sleep-ring ltr">{secondsLeft(session.sleepRemainingMs)}s</span>
          <span className="muted">{stateText(session, t)}</span></div>
      )}
      {session.progress && session.state !== 'SLEEPING' && <LiveView progress={session.progress} onSeek={playing ? seek : undefined} />}
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
            defaultValue={msToSeconds(item.sleepAfterMs)} onBlur={(e) => onSleep(e.target.value)} />
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
          onBlur={(e) => commit(e.target.value.trim(), item.port)} onKeyDown={blurOnEnter} />
      )}
      <input key={`p-${key}`} className="input mono" style={{ width: 84, height: 28 }} type="number" defaultValue={item.port}
        onBlur={(e) => commit(item.host, parseInt(e.target.value, 10))} onKeyDown={blurOnEnter} />
    </div>
  );
}
