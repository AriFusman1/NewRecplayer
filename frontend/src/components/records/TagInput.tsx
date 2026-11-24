import { useEffect, useMemo, useRef, useState, type Dispatch, type KeyboardEvent, type SetStateAction } from 'react';
import { ApiError, api } from '../../api/client';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { toggleIn } from '../../util/list';
import { findByName } from '../../util/names';
import { Icon } from '../common/Icon';

interface Props {
  /** Defaults to "Tags". */
  label?: string;
  selected: string[];
  onChange: Dispatch<SetStateAction<string[]>>;
}

/**
 * Tag chips filtered by what is typed; Enter or "+" selects the tag with that name, or creates it.
 * A selected tag that another user deletes is dropped, with a notice.
 */
export function TagInput({ label, selected, onChange }: Props) {
  const { t } = useI18n();
  const { catalog, tagById, loaded, reload } = useCatalog();
  const { toast } = useFeedback();
  const { run, busy } = useAction();
  const [query, setQuery] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const typed = query.trim();
  const exact = findByName(catalog.tags, typed);

  useEffect(() => {
    if (!loaded || selected.every((id) => tagById.has(id))) return;
    onChange((ids) => ids.filter((id) => tagById.has(id)));
    toast('warning', t('tags.selectedDeleted'));
  }, [loaded, selected, tagById]); // eslint-disable-line react-hooks/exhaustive-deps

  const visible = useMemo(() => {
    const needle = typed.toLocaleLowerCase();
    return [...catalog.tags]
      .filter((tag) => selected.includes(tag.id) || tag.name.toLocaleLowerCase().includes(needle))
      .sort((left, right) => left.name.localeCompare(right.name));
  }, [catalog.tags, selected, typed]);

  const add = (id: string) => onChange((ids) => (ids.includes(id) ? ids : [...ids, id]));

  const create = () => run(async () => {
    try {
      const tag = await api.createTag(typed);
      await reload();
      add(tag.id);
      toast('success', t('quick.created', { name: tag.name }));
    } catch (e) {
      // created by someone else a moment ago
      if (!(e instanceof ApiError && e.code === 'TAG_EXISTS')) throw e;
      const raced = findByName((await reload())?.tags ?? [], typed);
      if (!raced) throw e;
      add(raced.id);
      toast('info', t('quick.existingSelected', { name: raced.name }));
    }
    setQuery('');
  });

  const submit = () => {
    if (busy) return;

    if (!typed) {
      input.current?.focus();
    } else if (exact) {
      add(exact.id);
      setQuery('');
    } else {
      void create();
    }
  };

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      submit();
    } else if (e.key === 'Escape' && query) {
      e.stopPropagation(); // clear the text, keep the dialog open
      setQuery('');
    }
  };

  return (
    <div className="field">
      <label>{label ?? t('upload.tags')}</label>
      <div className="row">
        <input ref={input} className="input grow" placeholder={t('tags.search')} value={query}
          onChange={(e) => setQuery(e.target.value)} onKeyDown={onKeyDown} />
        <button className="btn icon" title={t('tags.new')} aria-label={t('tags.new')} disabled={busy} onClick={submit}><Icon name="plus" /></button>
      </div>
      {(visible.length > 0 || (typed && !exact)) && (
        <div className="row wrap">
          {visible.map((tag) => (
            <button key={tag.id} className={`chip selectable${selected.includes(tag.id) ? ' on' : ''}`}
              onClick={() => onChange((ids) => toggleIn(ids, tag.id))}><bdi>#{tag.name}</bdi></button>
          ))}
          {typed && !exact && (
            <button className="chip selectable" disabled={busy} onClick={submit}>
              <Icon name="plus" size={12} /><bdi>{t('tags.createNamed', { name: typed })}</bdi></button>
          )}
        </div>
      )}
      {catalog.tags.length === 0 && !typed && <span className="hint">{t('upload.noTags')}</span>}
    </div>
  );
}
