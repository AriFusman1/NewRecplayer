import { useMemo, useState } from 'react';
import { MODES, type Mode } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { usePlaylist } from '../../state/playlist';
import { Icon } from '../../components/common/Icon';
import { PlaylistDrawer } from '../../components/playlist/PlaylistDrawer';
import { RecordCard } from '../../components/records/RecordCard';
import { toggleIn } from '../../util/list';
import { matchesQuery, tagsForCategory } from '../../util/records';
import { parseMs } from '../../util/time';
import { Link } from 'react-router-dom';

/** Main page: search + filters + record cards, and the playlist drawer. */
export function PlayerPage() {
  const { t } = useI18n();
  const { catalog, categoryById, loaded } = useCatalog();
  const { items } = usePlaylist();
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [mode, setMode] = useState<Mode | ''>('');
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [drawer, setDrawer] = useState(false);

  const fromUs = from ? (parseMs(from) ?? NaN) * 1000 : null;
  const toUs = to ? (parseMs(to) ?? NaN) * 1000 + 999_999 : null;

  const visibleTags = tagsForCategory(catalog.tags, categoryId);

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

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{t('nav.player')}</h1>
          <p className="muted">{t('player.count', { n: records.length })}</p>
        </div>
        <button className="btn" onClick={() => setDrawer(true)}><Icon name="list" />{t('playlist.open', { n: items.length })}</button>
      </div>

      <div className="card flat">
        <div className="filters">
          <div className="search field">
            <Icon name="search" />
            <input className="input" value={query} placeholder={t('player.search')} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <select className="select" value={categoryId} onChange={(e) => { setCategoryId(e.target.value); setTagIds([]); }}>
            <option value="">{t('player.allCategories')}</option>
            {catalog.categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
          </select>
          <select className="select" value={mode} onChange={(e) => setMode(e.target.value as Mode | '')}>
            <option value="">{t('player.allModes')}</option>
            {MODES.map((modeOption) => <option key={modeOption} value={modeOption}>{t(`mode.${modeOption}`)}</option>)}
          </select>
          <input className={`input time${from && Number.isNaN(fromUs) ? ' invalid' : ''}`} value={from} placeholder={t('player.from')} title="DD/MM/YYYY HH:MM:SS" onChange={(e) => setFrom(e.target.value)} />
          <input className={`input time${to && Number.isNaN(toUs) ? ' invalid' : ''}`} value={to} placeholder={t('player.to')} title="DD/MM/YYYY HH:MM:SS" onChange={(e) => setTo(e.target.value)} />
          <button className="btn ghost" disabled={!filtered} onClick={clear}>{t('player.clear')}</button>
        </div>
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
          {records.map((record) => <RecordCard key={record.id} record={record} />)}
        </div>
      )}

      {items.length > 0 && !drawer && (
        <button className="btn primary playlist-fab" onClick={() => setDrawer(true)}><Icon name="list" />{t('playlist.open', { n: items.length })}</button>
      )}
      {drawer && <PlaylistDrawer onClose={() => setDrawer(false)} />}
    </div>
  );
}
