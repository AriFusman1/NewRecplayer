import { useCallback, useMemo, useState } from 'react';
import { MODES, type Mode } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { usePlaylist } from '../../state/playlist';
import { useRoom } from '../../state/live';
import { MIN_PLAY_TOGETHER } from '../../util/target';
import { Icon } from '../../components/common/Icon';
import { PlaylistDrawer } from '../../components/playlist/PlaylistDrawer';
import { DateTimeField } from '../../components/common/DateTimeField';
import { PlayTogetherDialog } from '../../components/playback/PlayTogetherDialog';
import { BulkTagDialog } from '../../components/records/BulkTagDialog';
import { RecordCard } from '../../components/records/RecordCard';
import { toggleIn } from '../../util/list';
import { matchesQuery } from '../../util/records';
import { parseBoundUs, readDateTime } from '../../util/time';
import { Link } from 'react-router-dom';

/** Main page: search + filters + record cards, the playlist drawer, and "Play together" (up to 10 records). */
export function PlayerPage() {
  const { t } = useI18n();
  const { catalog, categoryById, recordById, loaded } = useCatalog();
  const { items } = usePlaylist();
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [mode, setMode] = useState<Mode | ''>('');
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [drawer, setDrawer] = useState(false);
  /** Selection mode: the chosen records can get tags (any number) or be played together (2 up to the free slots). */
  const [selecting, setSelecting] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [bulkTagging, setBulkTagging] = useState(false);
  const [playTogether, setPlayTogether] = useState(false);
  const toggleSelected = useCallback((recordId: string) => setSelectedIds((ids) => toggleIn(ids, recordId)), []);
  // in the order they were picked; records deleted meanwhile drop out of the selection
  const selected = selectedIds.filter((id) => recordById.has(id));

  // The server allows a limited number of replays at once for everyone together, so starting a
  // group needs that many free slots (it checks again when Play is pressed).
  const { blockedFor } = useRoom();
  /** Why Play together cannot start now, or null. One record is played with Play. */
  const playBlocked = selected.length < MIN_PLAY_TOGETHER ? t('select.tooFew') : blockedFor(selected.length);

  // NaN = typed but not a valid date (the field turns red and the filter is ignored)
  const fromUs = from ? parseBoundUs(from, false) ?? NaN : null;
  const toUs = to ? parseBoundUs(to, true) ?? NaN : null;
  // "to" before "from" matches nothing: both fields turn red with an explanation
  const reversed = fromUs !== null && toUs !== null && fromUs > toUs;

  const visibleTags = catalog.tags;

  const records = useMemo(() => {
    return catalog.records
      .filter((record) => matchesQuery(record, query))
      .filter((record) => !categoryId || record.categoryId === categoryId)
      .filter((record) => !mode || categoryById.get(record.categoryId)?.mode === mode)
      .filter((record) => tagIds.every((id) => record.tagIds.includes(id)))
      .filter((record) => fromUs === null || Number.isNaN(fromUs) || record.endUs >= fromUs)
      .filter((record) => toUs === null || Number.isNaN(toUs) || record.startUs <= toUs)
      .sort((left, right) => right.createdAtMs - left.createdAtMs);
  }, [catalog.records, query, categoryId, mode, tagIds, fromUs, toUs, categoryById]);

  const filtered = query || categoryId || mode || tagIds.length || from || to;
  const clear = () => { setQuery(''); setCategoryId(''); setMode(''); setTagIds([]); setFrom(''); setTo(''); };
  const stopSelecting = () => { setSelecting(false); setSelectedIds([]); };
  const allVisibleSelected = records.length > 0 && records.every((record) => selected.includes(record.id));
  const toggleAllVisible = () => setSelectedIds(allVisibleSelected
    ? selected.filter((id) => !records.some((record) => record.id === id))
    : [...new Set([...selected, ...records.map((record) => record.id)])]);

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{t('nav.player')}</h1>
          <p className="muted">{t('player.count', { n: records.length })}</p>
        </div>
        <div className="row">
          {catalog.records.length > 0 && (
            <button className={`btn${selecting ? ' active' : ''}`} aria-pressed={selecting} onClick={() => (selecting ? stopSelecting() : setSelecting(true))}>
              <Icon name="check" />{t('bulk.select')}</button>
          )}
          <button className="btn" onClick={() => setDrawer(true)}><Icon name="list" />{t('playlist.open', { n: items.length })}</button>
        </div>
      </div>

      {selecting && (
        <div className="bulk-bar" role="toolbar" aria-label={t('bulk.select')}>
          <span className="strong">{t('bulk.selected', { n: selected.length })}</span>
          <button className="btn ghost sm" disabled={records.length === 0} onClick={toggleAllVisible}>
            {allVisibleSelected ? t('bulk.unselectVisible') : t('bulk.selectVisible', { n: records.length })}</button>
          <div className="grow" />
          {selected.length >= MIN_PLAY_TOGETHER && playBlocked && <span className="small" style={{ color: 'var(--warning)' }}>{playBlocked}</span>}
          {/* the tooltip sits on a wrapper: a disabled button gets no mouse events, so it would never show */}
          <span title={playBlocked ?? undefined}>
            <button className="btn sm" disabled={playBlocked !== null} onClick={() => setPlayTogether(true)}>
              <Icon name="play" size={12} />{t('select.playTogether', { n: selected.length })}</button>
          </span>
          <button className="btn primary sm" disabled={selected.length === 0} onClick={() => setBulkTagging(true)}>
            <Icon name="tag" size={14} />{t('bulk.editTags')}</button>
          <button className="btn ghost sm" onClick={stopSelecting}>{t('bulk.done')}</button>
        </div>
      )}

      <div className="card flat">
        <div className="filters">
          <div className="search field">
            <Icon name="search" />
            <input className="input" value={query} placeholder={t('player.search')} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <select className="select" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">{t('player.allCategories')}</option>
            {catalog.categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
          </select>
          <select className="select" value={mode} onChange={(e) => setMode(e.target.value as Mode | '')}>
            <option value="">{t('player.allModes')}</option>
            {MODES.map((modeOption) => <option key={modeOption} value={modeOption}>{t(`mode.${modeOption}`)}</option>)}
          </select>
          <div className="range-filter" title={t('player.rangeHint')}>
            <span className="range-label"><Icon name="calendar" size={15} />{t('player.recordedTime')}</span>
            <DateTimeField value={from} invalid={Number.isNaN(fromUs) || reversed} placeholder={t('player.from')} onChange={setFrom}
              dayTime="start" maxDay={readDateTime(to)} />
            <span className="muted" aria-hidden="true">–</span>
            <DateTimeField value={to} invalid={Number.isNaN(toUs) || reversed} placeholder={t('player.to')} onChange={setTo}
              dayTime="end" minDay={readDateTime(from)} />
          </div>
          <button className="btn ghost" disabled={!filtered} onClick={clear}>{t('player.clear')}</button>
        </div>
        {reversed && <p className="filter-error">{t('player.rangeReversed')}</p>}
        {visibleTags.length > 0 && (
          <div className="tag-filter">
            <span className="label" style={{ alignSelf: 'center' }}>{t('player.tags')}</span>
            {visibleTags.map((tag) => {
              const on = tagIds.includes(tag.id);
              return (
                <button key={tag.id} className={`chip selectable${on ? ' on' : ''}`}
                  onClick={() => setTagIds(toggleIn(tagIds, tag.id))}><bdi>#{tag.name}</bdi></button>
              );
            })}
          </div>
        )}
      </div>

      {loaded && catalog.records.length === 0 ? (
        <div className="empty" style={{ marginTop: 18 }}>
          <Icon name="upload" size={30} />
          <div>{t('player.empty')}</div>
          <Link className="btn primary" to="/upload"><Icon name="upload" />{t('nav.upload')}</Link>
        </div>
      ) : records.length === 0 && loaded ? (
        <div className="empty" style={{ marginTop: 18 }}>{t('player.noMatch')}</div>
      ) : (
        <div className="records">
          {records.map((record) => (
            <RecordCard key={record.id} record={record} onToggleSelect={selecting ? toggleSelected : undefined} selected={selected.includes(record.id)} />
          ))}
        </div>
      )}

      {playTogether && (
        <PlayTogetherDialog records={selected.map((id) => recordById.get(id)!)} onClose={() => setPlayTogether(false)} onStarted={stopSelecting} />
      )}

      {items.length > 0 && !drawer && (
        <button className="btn primary playlist-fab" onClick={() => setDrawer(true)}><Icon name="list" />{t('playlist.open', { n: items.length })}</button>
      )}
      {drawer && <PlaylistDrawer onClose={() => setDrawer(false)} />}
      {bulkTagging && <BulkTagDialog recordIds={selected} onClose={() => setBulkTagging(false)} />}
    </div>
  );
}
