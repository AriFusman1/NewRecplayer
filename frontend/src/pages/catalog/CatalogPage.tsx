import { useState, type ReactNode } from 'react';
import { api } from '../../api/client';
import { MODES, type Category, type Mode, type Tag } from '../../api/types';
import { ModeBadge } from '../../components/common/Badges';
import { Icon } from '../../components/common/Icon';
import { Confirm } from '../../components/common/Modal';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';

/** Manage categories (name + fixed connection mode) and tags (global or per category). */
export function CatalogPage() {
  const { t } = useI18n();
  return (
    <div>
      <div className="page-head"><h1>{t('nav.catalog')}</h1></div>
      <div className="grid-2" style={{ alignItems: 'start' }}>
        <CategoriesCard />
        <TagsCard />
      </div>
    </div>
  );
}

function CategoriesCard() {
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
  const doDelete = async () => {
    if (!deleting) return;
    await run(() => api.deleteCategory(deleting.category.id));
    setDeleting(null);
  };
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
            extra={<><ModeBadge mode={category.mode} /><span className="small muted">{t('cat.records', { n: counts.get(category.id) ?? 0 })}</span></>} />
        ))}
      </div>
      {deleting && (
        <Confirm title={t('cat.deleteTitle')} body={t('cat.deleteBody', { name: deleting.category.name, n: deleting.recordCount })} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')} onConfirm={doDelete} onCancel={() => setDeleting(null)} />
      )}
    </section>
  );
}

function TagsCard() {
  const { t } = useI18n();
  const { catalog, categoryById } = useCatalog();
  const { run } = useAction();
  const [name, setName] = useState('');
  const [scope, setScope] = useState('');
  const [deleting, setDeleting] = useState<Tag | null>(null);

  const create = () => run(async () => {
    await api.createTag(name.trim(), scope || null);
    setName('');
  });
  const doDelete = async (tag: Tag) => {
    await run(() => api.deleteTag(tag.id));
    setDeleting(null);
  };
  const groups: { key: string; label: string; tags: Tag[] }[] = [
    { key: '', label: t('tags.global'), tags: catalog.tags.filter((tag) => !tag.categoryId) },
    ...catalog.categories.map((category) => ({ key: category.id, label: category.name, tags: catalog.tags.filter((tag) => tag.categoryId === category.id) })),
  ].filter((group) => group.tags.length > 0);

  return (
    <section className="card">
      <div className="card-title"><h2>{t('tags.title')}</h2><span className="badge neutral">{catalog.tags.length}</span></div>
      <div className="col form-panel">
        <span className="section-title">{t('tags.new')}</span>
        <div className="row wrap">
          <input className="input grow" style={{ minWidth: 140 }} placeholder={t('cat.name')} value={name}
            onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && name.trim() && void create()} />
          <select className="select" style={{ width: 190 }} value={scope} onChange={(e) => setScope(e.target.value)} aria-label={t('tags.scope')}>
            <option value="">{t('tags.global')}</option>
            {catalog.categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
          </select>
          <button className="btn primary" disabled={!name.trim()} onClick={create}><Icon name="plus" />{t('common.create')}</button>
        </div>
      </div>
      {groups.length === 0 && <p className="muted" style={{ padding: 12 }}>{t('tags.empty')}</p>}
      {groups.map((group) => (
        <div key={group.key} style={{ marginTop: 14 }}>
          <span className="section-title">{group.label}</span>
          <div className="list">
            {group.tags.map((tag) => (
              <EditableRow key={tag.id} name={tag.name} prefix="#" onRename={(newName) => api.renameTag(tag.id, newName)} onDelete={() => setDeleting(tag)}
                extra={<span className={`chip tag${tag.categoryId ? '' : ' global'}`}>{tag.categoryId ? categoryById.get(tag.categoryId)?.name : t('tags.global')}</span>} />
            ))}
          </div>
        </div>
      ))}
      {deleting && (
        <Confirm title={t('tags.deleteTitle')} body={t('tags.deleteBody', { name: deleting.name })} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')}
          onConfirm={() => void doDelete(deleting)}
          onCancel={() => setDeleting(null)} />
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
