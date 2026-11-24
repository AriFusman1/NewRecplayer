import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useDebouncedCallback } from 'use-debounce';
import { Icon } from './Icon';
import { Popover } from './Popover';

/** Typing settles for this long before the text is applied. */
const TYPING_DEBOUNCE_MS = 300;

interface Props {
  /** The applied text (from the URL); the field follows it when it changes from outside. */
  value: string;
  onCommit: (value: string) => void;
  /** Values to offer for what is typed (already limited). */
  suggest: (typed: string) => string[];
  placeholder: string;
  ariaLabel: string;
  clearLabel: string;
}

/**
 * A text input with suggestions underneath. Typing filters as it is ("contains"); a suggestion is
 * only a shortcut. Keys: ↓/↑ move through the suggestions, Enter or Tab takes the highlighted one,
 * Esc closes the list.
 */
export function AutocompleteInput({ value, onCommit, suggest, placeholder, ariaLabel, clearLabel }: Props) {
  const [text, setText] = useState(value);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(-1);
  const wrapper = useRef<HTMLDivElement>(null);
  const commitLater = useDebouncedCallback(onCommit, TYPING_DEBOUNCE_MS);
  const options = useMemo(() => suggest(text), [suggest, text]);

  useEffect(() => setText(value), [value]);
  useEffect(() => setHighlight(-1), [text]);

  const choose = (chosen: string) => {
    commitLater.cancel();
    setText(chosen);
    onCommit(chosen);
    setOpen(false);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const listShown = open && options.length > 0;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      setHighlight((index) => Math.min(index + 1, options.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlight((index) => Math.max(index - 1, -1));
    } else if ((event.key === 'Enter' || event.key === 'Tab') && listShown && highlight >= 0) {
      if (event.key === 'Enter') event.preventDefault(); // Tab still moves on to the next filter
      choose(options[highlight]);
    } else if (event.key === 'Enter') {
      commitLater.flush(); // apply now instead of after the pause
      setOpen(false);
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div ref={wrapper} className={`filter-field${value.trim() ? ' active' : ''}`}>
      <input className="filter-input" value={text} placeholder={placeholder} aria-label={ariaLabel} title={text || undefined}
        role="combobox" aria-expanded={open && options.length > 0} aria-autocomplete="list"
        onFocus={() => setOpen(true)} onBlur={() => setOpen(false)} onKeyDown={onKeyDown}
        onChange={(event) => { setText(event.target.value); setOpen(true); commitLater(event.target.value); }} />
      {text && (
        <button className="filter-clear" aria-label={clearLabel} title={clearLabel}
          onMouseDown={(event) => event.preventDefault()} onClick={() => choose('')}><Icon name="x" size={11} /></button>
      )}
      {open && options.length > 0 && (
        <Popover anchor={wrapper.current} onClose={() => setOpen(false)} className="suggestions">
          <ul role="listbox" aria-label={ariaLabel}>
            {options.map((option, index) => (
              <li key={option} role="option" aria-selected={index === highlight} className={index === highlight ? 'highlighted' : undefined}
                onMouseDown={(event) => event.preventDefault()} onMouseEnter={() => setHighlight(index)} onClick={() => choose(option)}>
                <bdi>{option}</bdi>
              </li>
            ))}
          </ul>
        </Popover>
      )}
    </div>
  );
}
