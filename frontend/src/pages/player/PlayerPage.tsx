import { useCallback, useEffect, useMemo, useState } from 'react';
import { useDebounce } from 'use-debounce';
import { api } from '../../api/client';
import { MODES, type IcdTree, type Mode } from '../../api/types';
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
import { Link, useSearchParams } from 'react-router-dom';
import { useAction } from '../../state/feedback';

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
  // auto-tagging filters: answered by the server from its index of the records' message statistics
  const [rule, setRule] = useState('');
  const [opcode, setOpcode] = useState('');
  const [minCount, setMinCount] = useState('1');
  const [debouncedMin] = useDebounce(minCount, 300);
  const [tree, setTree] = useState<IcdTree | null>(null);
  const [statIds, setStatIds] = useState<Set<string> | null>(null);
  const { run } = useAction();
  // "?record=<id>" (a link from a tagging job) shows that one record
  const [searchParams, setSearchParams] = useSearchParams();
  const onlyRecord = searchParams.get('record');
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
  const ruleNames = useMemo(() => [...new Set(catalog.records.flatMap((record) => record.tagging?.matchedRules ?? []))].sort(),
    [catalog.records]);
  const icdId = categoryId ? categoryById.get(categoryId)?.icdId ?? null : null;

  // the message-type filter lists the types of the chosen category's ICD
  useEffect(() => {
    setOpcode('');
    setTree(null);
    if (icdId) void run(async () => setTree(await api.icdTree(icdId)));
  }, [icdId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!rule && !opcode) {
      setStatIds(null);
      return;
    }
    void run(async () => {
      const ids = await api.searchRecords({ categoryId: categoryId || undefined, rule: rule || undefined,
        opcode: opcode ? Number(opcode) : undefined, minCount: Math.max(1, Number(debouncedMin) || 1) });
      setStatIds(new Set(ids));
    });
  }, [rule, opcode, debouncedMin, categoryId, catalog.records]); // eslint-disable-line react-hooks/exhaustive-deps

  const records = useMemo(() => {
    return catalog.records
      .filter((record) => !onlyRecord || record.id === onlyRecord)
      .filter((record) => !statIds || statIds.has(record.id))
      .filter((record) => matchesQuery(record, query))
      .filter((record) => !categoryId || record.categoryId === categoryId)
      .filter((record) => !mode || categoryById.get(record.categoryId)?.mode === mode)
      .filter((record) => tagIds.every((id) => record.tagIds.includes(id)))
      .filter((record) => fromUs === null || Number.isNaN(fromUs) || record.endUs >= fromUs)
      .filter((record) => toUs === null || Number.isNaN(toUs) || record.startUs <= toUs)
      .sort((left, right) => right.createdAtMs - left.createdAtMs);
  }, [catalog.records, query, categoryId, mode, tagIds, fromUs, toUs, categoryById, statIds, onlyRecord]);

  const filtered = query || categoryId || mode || tagIds.length || from || to || rule || opcode || onlyRecord;
  const clear = () => {
    setQuery(''); setCategoryId(''); setMode(''); setTagIds([]); setFrom(''); setTo(''); setRule(''); setOpcode(''); setMinCount('1');
    if (onlyRecord) setSearchParams({});
  };
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
          {ruleNames.length > 0 && (
            <select className="select" value={rule} onChange={(e) => setRule(e.target.value)} aria-label={t('player.matchedRule')}>
              <option value="">{t('player.anyRule')}</option>
              {ruleNames.map((name) => <option key={name} value={name}>{name}</option>)}
            </select>
          )}
          {tree && (
            <div className="row" style={{ gap: 6 }} title={t('player.countHint')}>
              <select className="select" value={opcode} onChange={(e) => setOpcode(e.target.value)} aria-label={t('player.messageType')}>
                <option value="">{t('player.anyMessage')}</option>
                {tree.messages.map((message) => <option key={message.opcode} value={message.opcode}>{message.name}</option>)}
              </select>
              {opcode && (
                <>
                  <span className="muted">≥</span>
                  <input className="input" type="number" min={1} style={{ width: 90 }} value={minCount} aria-label={t('rule.minCount')}
                    onChange={(e) => setMinCount(e.target.value)} />
                </>
              )}
            </div>
          )}
          <button className="btn ghost" disabled={!filtered} onClick={clear}>{t('player.clear')}</button>
        </div>
        {reversed && <p className="filter-error">{t('player.rangeReversed')}</p>}
        {onlyRecord && (
          <div className="row small" style={{ gap: 8, marginTop: 8 }}>
            <span className="badge neutral">{t('player.oneRecord')}</span>
            <button className="btn ghost sm" onClick={() => setSearchParams({})}><Icon name="x" size={12} />{t('player.showAll')}</button>
          </div>
        )}
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
