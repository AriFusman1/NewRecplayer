import { useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, api } from '../../api/client';
import { MODES, type Category, type IcdSummary, type Mode, type Tag } from '../../api/types';
import { ModeBadge } from '../../components/common/Badges';
import { Icon } from '../../components/common/Icon';
import { Confirm } from '../../components/common/Modal';
import { IcdsCard } from '../../components/icd/IcdsCard';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';
import { useIcds } from '../../state/icds';
import { findByName } from '../../util/names';
import { DeleteCategoryDialog } from './DeleteCategoryDialog';

/**
 * Manage categories (name, fixed connection mode, ICD and its time fixing), global tags and ICDs.
 */
export function CatalogPage() {
  const { t } = useI18n();
  const { icds, reload } = useIcds();
  return (
    <div>
      <div className="page-head"><h1>{t('nav.catalog')}</h1></div>
      <div className="grid-2" style={{ alignItems: 'start' }}>
        <CategoriesCard icds={icds} />
        <div className="stack-lg">
          <TagsCard />
          <IcdsCard icds={icds} reload={reload} />
        </div>
      </div>
    </div>
  );
}

function CategoriesCard({ icds }: { icds: IcdSummary[] }) {
  const { t } = useI18n();
  const { catalog } = useCatalog();
  const { run } = useAction();
  const [name, setName] = useState('');
  const [mode, setMode] = useState<Mode>('TCP_SERVER');
  const [deleting, setDeleting] = useState<{ category: Category; recordCount: number } | null>(null);

  const create = () => run(async () => {
    await api.createCategory(name.trim(), mode);
    setName('');
  });
  const askDelete = (category: Category) => run(async () => setDeleting({ category, recordCount: (await api.categoryUsage(category.id)).records }));
  const counts = new Map<string, number>();
  catalog.records.forEach((record) => counts.set(record.categoryId, (counts.get(record.categoryId) ?? 0) + 1));

  return (
    <section className="card">
      <div className="card-title"><h2>{t('cat.title')}</h2><span className="badge neutral">{catalog.categories.length}</span></div>
      <div className="col form-panel">
        <span className="section-title">{t('cat.new')}</span>
        <div className="row wrap">
          <input className="input grow" style={{ minWidth: 160 }} placeholder={t('cat.name')} value={name}
            onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && name.trim() && void create()} />
          <select className="select" style={{ width: 170 }} value={mode} onChange={(e) => setMode(e.target.value as Mode)}>
            {MODES.map((modeOption) => <option key={modeOption} value={modeOption}>{t(`mode.${modeOption}`)}</option>)}
          </select>
          <button className="btn primary" disabled={!name.trim()} onClick={create}><Icon name="plus" />{t('common.create')}</button>
        </div>
        <span className="hint small muted">{t(`modeHint.${mode}`)} · {t('cat.modeFixed')}</span>
      </div>
      <div className="list" style={{ marginTop: 8 }}>
        {catalog.categories.length === 0 && <p className="muted" style={{ padding: 12 }}>{t('cat.empty')}</p>}
        {catalog.categories.map((category) => (
          <EditableRow key={category.id} name={category.name} onRename={(newName) => api.renameCategory(category.id, newName)} onDelete={() => askDelete(category)}
            extra={<><ModeBadge mode={category.mode} /><span className="small muted">{t('cat.records', { n: counts.get(category.id) ?? 0 })}</span>
              <CategoryIcd category={category} icds={icds} /></>} />
        ))}
      </div>
      {deleting && <DeleteCategoryDialog category={deleting.category} recordCount={deleting.recordCount} onClose={() => setDeleting(null)} />}
    </section>
  );
}

/** The category's ICD (a dropdown) and the way to its time-fix settings. */
function CategoryIcd({ category, icds }: { category: Category; icds: IcdSummary[] }) {
  const { t } = useI18n();
  const { run, busy } = useAction();
  const navigate = useNavigate();
  const known = !category.icdId || icds.some((icd) => icd.id === category.icdId);
  return (
    <>
      <select className="select" style={{ width: 150 }} value={category.icdId ?? ''} disabled={busy} aria-label={t('cat.icd')}
        title={t('cat.icd')} onChange={(e) => void run(() => api.setCategoryIcd(category.id, e.target.value || null))}>
        <option value="">{t('cat.noIcd')}</option>
        {!known && <option value={category.icdId ?? ''}>{category.icdId}</option>}
        {icds.map((icd) => <option key={icd.id} value={icd.id}>{icd.name}</option>)}
      </select>
      <button className="btn ghost icon sm" title={t('cat.timeFix')} disabled={!category.icdId}
        onClick={() => navigate(`/catalog/categories/${category.id}/timefix`)}><Icon name="clock" /></button>
    </>
  );
}

function TagsCard() {
  const { t } = useI18n();
  const { catalog } = useCatalog();
  const { run } = useAction();
  const [name, setName] = useState('');
  const [deleting, setDeleting] = useState<Tag | null>(null);
  const [merging, setMerging] = useState<{ from: Tag; into: Tag } | null>(null);

  const create = () => run(async () => {
    await api.createTag(name.trim());
    setName('');
  });
  const doDelete = async (tag: Tag) => {
    await run(() => api.deleteTag(tag.id));
    setDeleting(null);
  };
  /** Renaming to another tag's name offers to merge the two instead. */
  const rename = async (tag: Tag, newName: string) => {
    try {
      await api.renameTag(tag.id, newName);
    } catch (e) {
      const into = e instanceof ApiError && e.code === 'TAG_EXISTS' ? findByName(catalog.tags, newName) : undefined;
      if (!into || into.id === tag.id) throw e;
      setMerging({ from: tag, into });
    }
  };
  const doMerge = async () => {
    if (!merging) return;
    await run(() => api.mergeTag(merging.from.id, merging.into.id), t('tags.merged', { from: merging.from.name, into: merging.into.name }));
    setMerging(null);
  };

  return (
    <section className="card">
      <div className="card-title"><h2>{t('tags.title')}</h2><span className="badge neutral">{catalog.tags.length}</span></div>
      <div className="col form-panel">
        <span className="section-title">{t('tags.new')}</span>
        <div className="row wrap">
          <input className="input grow" style={{ minWidth: 140 }} placeholder={t('cat.name')} value={name}
            onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && name.trim() && void create()} />
          <button className="btn primary" disabled={!name.trim()} onClick={create}><Icon name="plus" />{t('common.create')}</button>
        </div>
      </div>
      {catalog.tags.length === 0 ? <p className="muted" style={{ padding: 12 }}>{t('tags.empty')}</p> : (
        <div className="list" style={{ marginTop: 14 }}>
          {[...catalog.tags].sort((left, right) => left.name.localeCompare(right.name)).map((tag) => (
            <EditableRow key={tag.id} name={tag.name} prefix="#" onRename={(newName) => rename(tag, newName)} onDelete={() => setDeleting(tag)} />
          ))}
        </div>
      )}
      {deleting && (
        <Confirm title={t('tags.deleteTitle')} body={t('tags.deleteBody', { name: deleting.name })} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')}
          onConfirm={() => void doDelete(deleting)}
          onCancel={() => setDeleting(null)} />
      )}
      {merging && (
        <Confirm title={t('tags.mergeTitle')} body={t('tags.mergeBody', { from: merging.from.name, into: merging.into.name })}
          confirmLabel={t('tags.merge')} cancelLabel={t('common.cancel')} onConfirm={() => void doMerge()} onCancel={() => setMerging(null)} />
      )}
    </section>
  );
}

/** A name with inline rename and a delete button. */
function EditableRow({ name, prefix = '', extra, onRename, onDelete }: {
  name: string; prefix?: string; extra?: ReactNode; onRename: (newName: string) => Promise<unknown>; onDelete: () => void;
}) {
  const { t } = useI18n();
  const { run } = useAction();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(name);
  const save = async () => {
    if (!value.trim() || value.trim() === name) return setEditing(false);
    if (await run(() => onRename(value.trim()))) setEditing(false);
  };
  return (
    <div className="list-row">
      {editing ? (
        <input className="input grow" autoFocus value={value} onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void save(); if (e.key === 'Escape') setEditing(false); }} onBlur={() => void save()} />
      ) : (
        <span className="grow ellipsis strong"><bdi>{prefix}{name}</bdi></span>
      )}
      {extra}
      <button className="btn ghost icon sm" title={t('common.rename')} onClick={() => { setValue(name); setEditing(true); }}><Icon name="edit" /></button>
      <button className="btn ghost icon sm" title={t('common.delete')} onClick={onDelete}><Icon name="trash" /></button>
    </div>
  );
}
