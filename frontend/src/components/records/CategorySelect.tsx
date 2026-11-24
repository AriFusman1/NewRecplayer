import { useEffect, useState, type KeyboardEvent } from 'react';
import { ApiError, api } from '../../api/client';
import { MODES, type Category, type Mode } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { findByName } from '../../util/names';
import { Icon } from '../common/Icon';

interface Props {
  value: string;
  onChange: (categoryId: string) => void;
  /** Editing a record: only categories with this mode are offered, and a new one gets it. */
  lockedMode?: Mode;
  /** Preselected mode for a new category (what the capture seems to need). */
  suggestedMode?: Mode;
}

/**
 * The category dropdown, with a "+" that creates a category in place and selects it. A selected
 * category that another user deletes is cleared, with a notice.
 */
export function CategorySelect({ value, onChange, lockedMode, suggestedMode }: Props) {
  const { t } = useI18n();
  const { catalog, categoryById, loaded } = useCatalog();
  const { toast } = useFeedback();
  const [adding, setAdding] = useState(false);
  const categories = lockedMode ? catalog.categories.filter((category) => category.mode === lockedMode) : catalog.categories;
  const selected = categoryById.get(value);

  useEffect(() => {
    if (loaded && value && !categoryById.has(value)) {
      onChange('');
      toast('warning', t('cat.selectedDeleted'));
    }
  }, [loaded, value, categoryById]); // eslint-disable-line react-hooks/exhaustive-deps

  const created = (category: Category) => {
    onChange(category.id);
    setAdding(false);
  };

  return (
    <div className="field">
      <label>{t('upload.category')}</label>
      <div className="row">
        <select className="select grow" value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">{t('upload.selectCategory')}</option>
          {categories.map((category) => <option key={category.id} value={category.id}>{category.name} — {t(`mode.${category.mode}`)}</option>)}
        </select>
        <button className="btn icon" title={t('cat.new')} aria-label={t('cat.new')} aria-expanded={adding} onClick={() => setAdding(!adding)}>
          <Icon name={adding ? 'x' : 'plus'} /></button>
      </div>
      {adding && <NewCategoryForm lockedMode={lockedMode} suggestedMode={suggestedMode} onCreated={created} />}
      {!adding && categories.length === 0 && (
        <div className="row wrap">
          <span className="hint">{t('upload.noCategories')}</span>
          <button className="btn sm" onClick={() => setAdding(true)}><Icon name="plus" />{t('upload.firstCategory')}</button>
        </div>
      )}
      {!adding && selected && <span className="hint">{t(`modeHint.${selected.mode}`)}</span>}
    </div>
  );
}

/** Name + mode; a name that already exists selects that category when its mode fits. */
function NewCategoryForm({ lockedMode, suggestedMode, onCreated }: {
  lockedMode?: Mode; suggestedMode?: Mode; onCreated: (category: Category) => void;
}) {
  const { t } = useI18n();
  const { catalog, reload } = useCatalog();
  const { toast, popup } = useFeedback();
  const { run, busy } = useAction();
  const [name, setName] = useState('');
  const [mode, setMode] = useState<Mode>(lockedMode ?? suggestedMode ?? 'TCP_SERVER');

  const pickExisting = (category: Category) => {
    if (category.mode !== mode) {
      popup(t('err.title'), t('cat.existsOtherMode', { name: category.name, mode: t(`mode.${category.mode}`) }));
      return;
    }
    toast('info', t('quick.existingSelected', { name: category.name }));
    onCreated(category);
  };

  const create = () => run(async () => {
    const existing = findByName(catalog.categories, name);
    if (existing) return pickExisting(existing);

    try {
      const category = await api.createCategory(name.trim(), mode);
      await reload();
      toast('success', t('quick.created', { name: category.name }));
      onCreated(category);
    } catch (e) {
      // created by someone else a moment ago
      if (!(e instanceof ApiError && e.code === 'CATEGORY_EXISTS')) throw e;
      const raced = findByName((await reload())?.categories ?? [], name);
      if (!raced) throw e;
      pickExisting(raced);
    }
  });

  const submit = () => {
    if (name.trim() && !busy) void create();
  };

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Enter') submit();
  };

  return (
    <div className="col form-panel">
      <span className="section-title">{t('cat.new')}</span>
      <div className="row wrap">
        <input className="input grow" style={{ minWidth: 140 }} autoFocus placeholder={t('cat.name')} value={name}
          onChange={(e) => setName(e.target.value)} onKeyDown={onKeyDown} />
        <select className="select" style={{ width: 150 }} value={mode} disabled={!!lockedMode} onChange={(e) => setMode(e.target.value as Mode)}>
          {MODES.map((modeOption) => <option key={modeOption} value={modeOption}>{t(`mode.${modeOption}`)}</option>)}
        </select>
        <button className="btn primary" disabled={!name.trim() || busy} onClick={submit}><Icon name="plus" />{t('common.create')}</button>
      </div>
      <span className="hint">{t(`modeHint.${mode}`)} · {lockedMode ? t('cat.modeLocked') : t('cat.modeFixed')}</span>
    </div>
  );
}
