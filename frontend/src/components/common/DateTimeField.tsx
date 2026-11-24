import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type RefObject } from 'react';
import { DayPicker } from 'react-day-picker';
import { enUS, he } from 'react-day-picker/locale';
import 'react-day-picker/style.css';
import { useI18n } from '../../i18n';
import { compareDays, readDateTime, todayParts, writeDateTime, type DateTimeParts } from '../../util/time';
import { Icon } from './Icon';

type Time = NonNullable<DateTimeParts['time']>;

const START_OF_DAY: Time = { hour: 0, minute: 0, second: 0 };
const END_OF_DAY: Time = { hour: 23, minute: 59, second: 59 };

interface Props {
  value: string;
  onChange: (text: string) => void;
  placeholder?: string;
  invalid?: boolean;
  /** The time is part of the value: picking a day keeps the time already there, and there is no Clear. */
  requireTime?: boolean;
  /**
   * The time a picked day gets when the field has none: 'start' = 00:00:00 (a "from" field),
   * 'end' = 23:59:59 (a "to" field). The clock also starts from it.
   */
  dayTime?: 'start' | 'end';
  /** Days before this one are disabled (time ignored). */
  minDay?: DateTimeParts | null;
  /** Days after this one are disabled (time ignored). */
  maxDay?: DateTimeParts | null;
}

/**
 * A date/time text field (DD/MM/YYYY or DD/MM/YYYY HH:MM:SS) with a calendar button. The popup
 * has a month calendar and a digital HH:MM:SS clock; both write into the text, and typing in the
 * text moves them. The text stays the value, so the field can always be edited by hand.
 */
export function DateTimeField({ value, onChange, placeholder, invalid, requireTime, dayTime = 'start', minDay, maxDay }: Props) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  // close on a click outside
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && open) {
      e.stopPropagation(); // close the popup, not the dialog around it
      setOpen(false);
    }
  };

  return (
    <div className="dtf" ref={root} onKeyDown={onKeyDown}>
      <div className={`dtf-box${invalid ? ' invalid' : ''}${open ? ' open' : ''}`}>
        <input className={`dtf-input${value ? ' filled' : ''}`} value={value} placeholder={placeholder} aria-label={placeholder}
          spellCheck={false} onChange={(e) => onChange(e.target.value)} />
        <button type="button" className="dtf-toggle" title={t('dtp.open')} aria-label={t('dtp.open')} aria-expanded={open}
          onClick={() => setOpen(!open)}><Icon name="calendar" size={15} /></button>
      </div>
      {open && <Popup anchor={root} value={value} onChange={onChange} requireTime={requireTime} dayTime={dayTime}
        minDay={minDay ?? null} maxDay={maxDay ?? null} onClose={() => setOpen(false)} />}
    </div>
  );
}

const GAP = 6;
const MARGIN = 8;

/**
 * Where the popup goes: fixed under the field (above it when there is no room below), aligned to
 * the field's start edge and kept on screen. Fixed, so a scrolling dialog does not clip it.
 */
function usePopupPosition(anchor: RefObject<HTMLElement>, popup: RefObject<HTMLElement>, dir: 'ltr' | 'rtl') {
  const [style, setStyle] = useState<CSSProperties>({ visibility: 'hidden' });

  useLayoutEffect(() => {
    const place = () => {
      const field = anchor.current?.getBoundingClientRect();
      const box = popup.current?.getBoundingClientRect();
      if (!field || !box) return;
      const below = window.innerHeight - field.bottom - GAP - MARGIN >= box.height;
      const top = below ? field.bottom + GAP : Math.max(MARGIN, field.top - GAP - box.height);
      const start = dir === 'rtl' ? field.right - box.width : field.left;
      const left = Math.min(Math.max(MARGIN, start), window.innerWidth - box.width - MARGIN);
      setStyle({ top, left });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [anchor, popup, dir]);
  return style;
}

/** The day closest to `day` inside [minDay, maxDay]. */
function clampDay(day: DateTimeParts, minDay: DateTimeParts | null, maxDay: DateTimeParts | null): DateTimeParts {
  if (minDay && compareDays(day, minDay) < 0) return { ...minDay, time: null };
  if (maxDay && compareDays(day, maxDay) > 0) return { ...maxDay, time: null };
  return day;
}

function toDate(day: DateTimeParts): Date {
  return new Date(day.year, day.month - 1, day.day);
}

function fromDate(date: Date): DateTimeParts {
  return { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(), time: null };
}

function Popup({ anchor, value, onChange, requireTime, dayTime, minDay, maxDay, onClose }: {
  anchor: RefObject<HTMLElement>; value: string; onChange: (text: string) => void; requireTime?: boolean;
  dayTime: 'start' | 'end'; minDay: DateTimeParts | null; maxDay: DateTimeParts | null; onClose: () => void;
}) {
  const { t, lang, dir } = useI18n();
  const box = useRef<HTMLDivElement>(null);
  const position = usePopupPosition(anchor, box, dir);
  const parts = readDateTime(value);
  const defaultTime = dayTime === 'end' ? END_OF_DAY : START_OF_DAY;
  const today = todayParts();
  const todayAllowed = compareDays(clampDay(today, minDay, maxDay), today) === 0;
  /** The day the calendar and clock work on: the typed one, else today kept inside the allowed days. */
  const shown = parts ?? clampDay(today, minDay, maxDay);
  const [month, setMonth] = useState(new Date(shown.year, shown.month - 1, 1));

  // typing a date in another month turns the calendar to it
  useEffect(() => {
    if (parts) setMonth(new Date(parts.year, parts.month - 1, 1));
  }, [parts?.year, parts?.month]); // eslint-disable-line react-hooks/exhaustive-deps

  /** A picked day keeps the time already typed, else gets the field's default (00:00:00 or 23:59:59). */
  const pickDay = (date: Date | undefined) => {
    if (date) onChange(writeDateTime({ ...fromDate(date), time: parts?.time ?? defaultTime }));
  };

  /** A clock change; without a date yet, it applies to the shown day. */
  const setTime = (time: Time) => onChange(writeDateTime({ ...shown, time }));

  const outside = (date: Date) => {
    const day = fromDate(date);
    return (minDay !== null && compareDays(day, minDay) < 0) || (maxDay !== null && compareDays(day, maxDay) > 0);
  };

  return (
    <div className="dtf-popup" ref={box} style={position} role="dialog" aria-label={t('dtp.title')}>
      <DayPicker mode="single" required={false} selected={parts ? toDate(parts) : undefined}
        onSelect={pickDay} month={month} onMonthChange={setMonth} locale={lang === 'he' ? he : enUS} dir={dir}
        weekStartsOn={0} showOutsideDays fixedWeeks disabled={outside} />
      <Clock time={parts?.time ?? null} base={defaultTime} onChange={setTime} />
      <div className="dtf-foot">
        {!requireTime && <button type="button" className="btn ghost sm" disabled={!value} onClick={() => onChange('')}>{t('dtp.clear')}</button>}
        <div className="grow" />
        {todayAllowed && <button type="button" className="btn ghost sm" onClick={() => pickDay(toDate(today))}>{t('dtp.today')}</button>}
        <button type="button" className="btn sm" onClick={onClose}>{t('common.close')}</button>
      </div>
    </div>
  );
}

const UNITS = [
  { key: 'hour', max: 23, label: 'dtp.hours' },
  { key: 'minute', max: 59, label: 'dtp.minutes' },
  { key: 'second', max: 59, label: 'dtp.seconds' },
] as const;

/**
 * HH : MM : SS, each with up/down buttons (wrapping around) and typing; always left-to-right.
 * With no time yet it shows "--" and the first change starts from `base`.
 */
function Clock({ time, base, onChange }: { time: Time | null; base: Time; onChange: (time: Time) => void }) {
  const { t } = useI18n();
  const current = time ?? base;
  const pad = (value: number) => String(value).padStart(2, '0');

  const step = (key: typeof UNITS[number]['key'], max: number, delta: number) =>
    onChange(time ? { ...time, [key]: (time[key] + delta + max + 1) % (max + 1) } : base);

  const type = (key: typeof UNITS[number]['key'], max: number, text: string) => {
    const typed = Number(text.replace(/\D/g, '').slice(-2));
    if (text.trim() !== '' && typed <= max) onChange({ ...current, [key]: typed });
  };

  return (
    <div className={`dtf-clock${time ? '' : ' unset'}`} dir="ltr">
      <Icon name="clock" size={15} />
      {UNITS.map(({ key, max, label }, i) => (
        <div key={key} className="dtf-unit-wrap">
          {i > 0 && <span className="dtf-colon">:</span>}
          <div className="dtf-unit">
            <button type="button" aria-label={`${t(label)} +1`} onClick={() => step(key, max, 1)}><Icon name="chevronUp" size={14} /></button>
            <input value={time ? pad(current[key]) : '--'} inputMode="numeric" aria-label={t(label)}
              onFocus={(e) => e.target.select()} onChange={(e) => type(key, max, e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
                  e.preventDefault();
                  step(key, max, e.key === 'ArrowUp' ? 1 : -1);
                }
              }} />
            <button type="button" aria-label={`${t(label)} −1`} onClick={() => step(key, max, -1)}><Icon name="chevronDown" size={14} /></button>
          </div>
        </div>
      ))}
    </div>
  );
}
