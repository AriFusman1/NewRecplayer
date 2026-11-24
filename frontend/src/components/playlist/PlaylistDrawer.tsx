import { useState } from 'react';
import { api } from '../../api/client';
import type { Mode, PcapRecord, SavedPlaylist, SavedPlaylistItem } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { usePlaylist, type DraftItem } from '../../state/playlist';
import { formatMs } from '../../util/time';
import { msToSeconds, secondsToMs, targetValid, toItemRequest } from '../../util/target';
import { rememberTargets } from '../../util/targetMemory';
import { ModeBadge } from '../common/Badges';
import { Icon } from '../common/Icon';
import { Confirm, Modal } from '../common/Modal';
import { openNowPlaying } from '../playback/NowPlaying';
import { RecordPicker } from '../records/RecordPicker';
import { SortableList, SortableRow, type SortableRowProps } from '../common/Sortable';
import { TargetFields } from '../playback/TargetFields';

const sameItems = (left: SavedPlaylistItem[], right: SavedPlaylistItem[]) =>
  left.length === right.length && left.every((item, i) => item.recordId === right[i].recordId && (item.host ?? null) === (right[i].host ?? null)
    && item.port === right[i].port && item.sleepAfterMs === right[i].sleepAfterMs);

type PendingConfirm = { kind: 'load' | 'delete'; playlist: SavedPlaylist };

/**
 * Side drawer for building a playlist. The draft lives in this browser; named playlists are saved
 * on the server (shared by everyone) and can be loaded back into the draft.
 */
export function PlaylistDrawer({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const { items, savedId, setSavedId, load, addMany, remove, move, update, clear } = usePlaylist();
  const { catalog, recordById, modeOf } = useCatalog();
  const { nickname } = useNickname();
  const { toast } = useFeedback();
  const { run, busy } = useAction();
  const [picker, setPicker] = useState(false);
  const [nameDialog, setNameDialog] = useState<{ initial: string } | null>(null);
  const [confirm, setConfirm] = useState<PendingConfirm | null>(null);

  const saved = savedId ? catalog.playlists.find((playlist) => playlist.id === savedId) ?? null : null;
  const draft = items.map(toItemRequest);
  const dirty = saved ? !sameItems(draft, saved.items) : items.length > 0;
  const rows = items.map((draftItem) => ({ draftItem, record: recordById.get(draftItem.recordId), mode: modeOf(draftItem.recordId) }));
  const anyMissing = rows.some((row) => !row.record || !row.mode);
  const allValid = rows.length > 0 && !anyMissing && rows.every((row) => targetValid(row.mode!, row.draftItem.host, row.draftItem.port));

  const play = async () => {
    if (await run(() => api.start(draft, true, nickname), t('playlist.play'))) {
      rememberTargets(draft);
      onClose();
      openNowPlaying();
    }
  };

  const loadSaved = (playlist: SavedPlaylist) => {
    load(playlist.id, playlist.items);
    toast('info', t('saved.loaded', { name: playlist.name }));
  };
  const pickSaved = (id: string) => {
    const playlist = catalog.playlists.find((candidate) => candidate.id === id);
    if (!playlist) return setSavedId(null);
    if (dirty && items.length > 0) setConfirm({ kind: 'load', playlist: playlist });
    else loadSaved(playlist);
  };

  /** Updates the loaded playlist, or (asNew) creates a new one under `name`. */
  const save = (name: string, asNew: boolean) => void run(async () => {
    const playlist = !asNew && saved
      ? await api.updatePlaylist(saved.id, name, draft, nickname)
      : await api.createPlaylist(name, draft, nickname);
    setSavedId(playlist.id);
    setNameDialog(null);
    toast('success', t('saved.saved', { name: playlist.name }));
  });

  const deleteSaved = (playlist: SavedPlaylist) => {
    setConfirm(null);
    void run(async () => {
      await api.deletePlaylist(playlist.id);
      if (savedId === playlist.id) setSavedId(null);
      toast('info', t('saved.deleted', { name: playlist.name }));
    });
  };

  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <aside className="drawer">
        <div className="drawer-head">
          <div className="row"><Icon name="list" size={18} /><h2>{t('playlist.title')}</h2><span className="badge neutral">{items.length}</span></div>
          <button className="btn ghost icon sm" onClick={onClose}><Icon name="x" /></button>
        </div>

        <SavedPlaylistBar saved={saved} dirty={dirty} canSave={!busy && items.length > 0 && !anyMissing}
          onPick={pickSaved} onSave={() => saved && save(saved.name, false)}
          onSaveAs={() => setNameDialog({ initial: saved ? t('saved.copyName', { name: saved.name }) : '' })}
          onDelete={() => saved && setConfirm({ kind: 'delete', playlist: saved })} />

        <div className="drawer-body">
          <button className="btn" style={{ alignSelf: 'flex-start' }} disabled={catalog.records.length === 0} onClick={() => setPicker(true)}>
            <Icon name="plus" size={14} />{t('picker.open')}</button>
          {items.length === 0 && <div className="empty"><Icon name="list" size={28} />{t('playlist.empty')}</div>}
          {items.length > 1 && <span className="small muted">{t('playlist.dragHint')}</span>}
          <SortableList ids={items.map((draftItem) => draftItem.key)} onMove={move}>
            {rows.map(({ draftItem, record, mode }, i) => (
              <SortableRow key={draftItem.key} id={draftItem.key}>
                {(sortable) => (
                  <DraftItemRow index={i} item={draftItem} record={record} mode={mode} isLast={i === items.length - 1}
                    sortable={sortable} onChange={(patch) => update(draftItem.key, patch)} onRemove={() => remove(draftItem.key)} />
                )}
              </SortableRow>
            ))}
          </SortableList>
        </div>
        <div className="drawer-foot">
          <button className="btn ghost" disabled={!items.length} onClick={clear}>{t('playlist.clear')}</button>
          <div className="grow" />
          <button className="btn primary" disabled={!allValid || busy} onClick={play}><Icon name="play" size={12} />{t('playlist.play')}</button>
        </div>
      </aside>

      {picker && <RecordPicker onAdd={addMany} onClose={() => setPicker(false)} />}
      {nameDialog && <NameDialog initial={nameDialog.initial} busy={busy} onSave={(name) => save(name, true)} onCancel={() => setNameDialog(null)} />}
      {confirm?.kind === 'load' && (
        <Confirm title={t('saved.replaceTitle')} body={t('saved.replaceBody', { name: confirm.playlist.name })}
          confirmLabel={t('saved.load')} cancelLabel={t('common.cancel')}
          onConfirm={() => { loadSaved(confirm.playlist); setConfirm(null); }} onCancel={() => setConfirm(null)} />
      )}
      {confirm?.kind === 'delete' && (
        <Confirm title={t('saved.deleteTitle')} body={confirm.playlist.name} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')}
          onConfirm={() => deleteSaved(confirm.playlist)} onCancel={() => setConfirm(null)} />
      )}
    </>
  );
}

/** Choose a saved playlist to load; save, save-as and delete the loaded one. */
function SavedPlaylistBar({ saved, dirty, canSave, onPick, onSave, onSaveAs, onDelete }: {
  saved: SavedPlaylist | null; dirty: boolean; canSave: boolean;
  onPick: (id: string) => void; onSave: () => void; onSaveAs: () => void; onDelete: () => void;
}) {
  const { t } = useI18n();
  const { catalog } = useCatalog();
  const playlists = [...catalog.playlists].sort((left, right) => left.name.localeCompare(right.name));
  const status = saved
    ? t('saved.meta', { by: saved.updatedBy || saved.createdBy, at: formatMs(saved.updatedAtMs) })
    : playlists.length === 0 ? t('saved.none') : t('saved.pickHint');

  return (
    <div className="saved-bar">
      <div className="row" style={{ gap: 6 }}>
        <select className="select grow" value={saved?.id ?? ''} onChange={(e) => onPick(e.target.value)}>
          <option value="">{t('saved.unsaved')}</option>
          {playlists.map((playlist) => <option key={playlist.id} value={playlist.id}>{playlist.name} ({playlist.items.length})</option>)}
        </select>
        {saved && <button className="btn ghost icon sm" title={t('common.delete')} onClick={onDelete}><Icon name="trash" /></button>}
      </div>
      <div className="row wrap small" style={{ gap: 6, justifyContent: 'space-between' }}>
        <span className="muted">
          {status}
          {saved && dirty && <span className="badge warn" style={{ marginInlineStart: 6 }}>{t('saved.changed')}</span>}
        </span>
        <div className="row" style={{ gap: 6 }}>
          {saved && <button className="btn sm primary" disabled={!canSave || !dirty} onClick={onSave}>{t('common.save')}</button>}
          <button className="btn sm" disabled={!canSave} onClick={onSaveAs}>{saved ? t('saved.saveAs') : t('saved.saveNew')}</button>
        </div>
      </div>
    </div>
  );
}

/** One draggable playlist item with its target, and the sleep before the next item. */
function DraftItemRow({ index, item, record, mode, isLast, sortable, onChange, onRemove }: {
  index: number; item: DraftItem; record: PcapRecord | undefined; mode: Mode | undefined; isLast: boolean;
  sortable: SortableRowProps; onChange: (patch: Partial<DraftItem>) => void; onRemove: () => void;
}) {
  const { t } = useI18n();
  return (
    <div {...sortable.rowProps}>
      <div className={`pl-item draft${sortable.dragging ? ' dragging' : ''}`}>
        <span className="handle" style={{ paddingTop: 4 }} {...sortable.handleProps}><Icon name="grip" /></span>
        <span className="idx">{index + 1}</span>
        <div className="col tight">
          {record && mode ? (
            <>
              <div className="row"><span className="ellipsis strong">{record.name}</span><ModeBadge mode={mode} /></div>
              <TargetFields compact mode={mode} host={item.host} port={item.port} onChange={(host, port) => onChange({ host, port })} />
            </>
          ) : <span className="muted">{t('playlist.missing')}</span>}
        </div>
        <button className="btn ghost icon sm" onClick={onRemove}><Icon name="x" /></button>
      </div>
      {!isLast && (
        <div className="pl-sleep">
          {t('playlist.sleep')}
          <input className="input mono" type="number" min={0} step={0.5} value={msToSeconds(item.sleepAfterMs)}
            onChange={(e) => onChange({ sleepAfterMs: secondsToMs(e.target.value) })} />
          {t('common.seconds')}
        </div>
      )}
    </div>
  );
}

function NameDialog({ initial, busy, onSave, onCancel }: { initial: string; busy: boolean; onSave: (name: string) => void; onCancel: () => void }) {
  const { t } = useI18n();
  const [name, setName] = useState(initial);
  const valid = name.trim().length > 0;
  return (
    <Modal title={t('saved.nameTitle')} onClose={onCancel}
      footer={<>
        <button className="btn ghost" onClick={onCancel}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!valid || busy} onClick={() => onSave(name.trim())}>{t('common.save')}</button>
      </>}>
      <div className="field">
        <label>{t('saved.name')}</label>
        <input className="input" autoFocus value={name} onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && valid && !busy && onSave(name.trim())} />
        <span className="hint">{t('saved.sharedHint')}</span>
      </div>
    </Modal>
  );
}
