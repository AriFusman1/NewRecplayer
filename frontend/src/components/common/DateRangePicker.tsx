import { useRef, useState } from 'react';
import { useI18n } from '../../i18n';
import {
  atTime, DAY_END, DAY_START, dayOf, formatRange, formatTime, lastDays, monthGrid, sameDay, today, type Day, type TimeRange,
} from '../../util/dateRange';
import { Icon } from './Icon';
import { Popover } from './Popover';

const TZ = 'Asia/Jerusalem';
const LAST_DAYS = 7;
const END_OF_SECOND_MS = 999;

interface Props {
  value: TimeRange;
  onChange: (range: TimeRange) => void;
  ariaLabel: string;
}

/**
 * A compact field for a from/to range that opens a calendar (Israel time). The first click picks
 * the first day, the second the last day; optional times narrow the range within those days.
 */
export function DateRangePicker({ value, onChange, ariaLabel }: Props) {
  const { t } = useI18n();
  const field = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const label = formatRange(value);
  const active = value.from !== null || value.to !== null;

  return (
    <div className={`filter-field${active ? ' active' : ''}`}>
      <button ref={field} className="filter-input filter-button" aria-label={ariaLabel} aria-expanded={open}
        title={label || undefined} onClick={() => setOpen(!open)}>
        <Icon name="clock" size={12} />
        <span className={`ellipsis${label ? '' : ' placeholder'}`}>{label || t('historyFilter.anyTime')}</span>
      </button>
      {active && (
        <button className="filter-clear" aria-label={t('historyFilter.remove')} title={t('historyFilter.remove')}
          onClick={() => onChange({ from: null, to: null })}><Icon name="x" size={11} /></button>
      )}
      {open && (
        <Popover anchor={field.current} onClose={() => setOpen(false)} className="calendar-popover">
          <Calendar value={value} onChange={onChange} />
        </Popover>
      )}
    </div>
  );
}

/** Days are the whole range; the times (default 00:00:00 and 23:59:59) apply to its first and last day. */
function Calendar({ value, onChange }: { value: TimeRange; onChange: (range: TimeRange) => void }) {
  const { t, lang } = useI18n();
  const now = Date.now();
  const [first, setFirst] = useState<Day | null>(value.from !== null ? dayOf(value.from) : null);
  const [last, setLast] = useState<Day | null>(value.to !== null ? dayOf(value.to) : null);
  const [fromTime, setFromTime] = useState(value.from !== null ? formatTime(value.from) : DAY_START);
  const [toTime, setToTime] = useState(value.to !== null ? formatTime(value.to) : DAY_END);
  const shown = first ?? last ?? dayOf(now);
  const [month, setMonth] = useState({ year: shown.year, month: shown.month });

  const locale = lang === 'he' ? 'he-IL' : 'en-GB';
  const monthTitle = new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: TZ })
    .format(new Date(Date.UTC(month.year, month.month, 15, 12)));
  const weekdays = Array.from({ length: 7 }, (_, weekday) =>
    new Intl.DateTimeFormat(locale, { weekday: 'narrow', timeZone: 'UTC' }).format(new Date(Date.UTC(2026, 10, 1 + weekday))));

  const dayKey = (day: Day) => day.year * 10000 + day.month * 100 + day.day; // only for ordering days
  const apply = (nextFirst: Day | null, nextLast: Day | null, nextFrom = fromTime, nextTo = toTime) => {
    const to = nextLast ? atTime(nextLast, nextTo) : null;
    onChange({ from: nextFirst ? atTime(nextFirst, nextFrom) : null, to: to === null ? null : to + END_OF_SECOND_MS });
  };
  const pick = (day: Day) => {
    if (!first || last) { // start a new range: from this day on
      setFirst(day); setLast(null);
      apply(day, null);
    } else { // second click: the range's other end (either order)
      const [start, end] = dayKey(day) < dayKey(first) ? [day, first] : [first, day];
      setFirst(start); setLast(end);
      apply(start, end);
    }
  };
  const quick = (range: TimeRange) => {
    setFirst(range.from !== null ? dayOf(range.from) : null);
    setLast(range.to !== null ? dayOf(range.to) : null);
    setFromTime(DAY_START); setToTime(DAY_END);
    onChange(range);
  };
  const changeTime = (which: 'from' | 'to', time: string) => {
    if (which === 'from') setFromTime(time); else setToTime(time);
    const valid = atTime({ year: 2000, month: 0, day: 1 }, time) !== null;
    if (valid) apply(first, last, which === 'from' ? time : fromTime, which === 'to' ? time : toTime);
  };
  const moveMonth = (step: number) => setMonth(({ year, month: current }) => {
    const index = year * 12 + current + step;
    return { year: Math.floor(index / 12), month: index % 12 };
  });
  const inRange = (day: Day) => first && last && dayKey(day) >= dayKey(first) && dayKey(day) <= dayKey(last);
  const todayDay = dayOf(now);

  return (
    <div className="calendar">
      <div className="calendar-head">
        <button className="btn ghost icon sm" aria-label={t('historyFilter.prevMonth')} onClick={() => moveMonth(-1)}>
          <Icon name="chevronDown" size={14} className="rotate-start" /></button>
        <strong>{monthTitle}</strong>
        <button className="btn ghost icon sm" aria-label={t('historyFilter.nextMonth')} onClick={() => moveMonth(1)}>
          <Icon name="chevronDown" size={14} className="rotate-end" /></button>
      </div>
      <div className="calendar-grid">
        {weekdays.map((weekday, index) => <span key={index} className="weekday">{weekday}</span>)}
        {monthGrid(month.year, month.month).flat().map((day) => {
          const classes = ['day'];
          if (day.month !== month.month) classes.push('other-month');
          if (sameDay(day, todayDay)) classes.push('today');
          if (inRange(day)) classes.push('in-range');
          if ((first && sameDay(day, first)) || (last && sameDay(day, last))) classes.push('selected');
          return (
            <button key={`${day.month}-${day.day}`} className={classes.join(' ')} onClick={() => pick(day)}>{day.day}</button>
          );
        })}
      </div>
      <div className="calendar-times">
        <label>{t('historyFilter.fromTime')}
          <input className={`input time${atTime(todayDay, fromTime) === null ? ' invalid' : ''}`} value={fromTime}
            onChange={(event) => changeTime('from', event.target.value)} /></label>
        <label>{t('historyFilter.toTime')}
          <input className={`input time${atTime(todayDay, toTime) === null ? ' invalid' : ''}`} value={toTime}
            onChange={(event) => changeTime('to', event.target.value)} /></label>
      </div>
      <div className="calendar-quick">
        <button className="btn ghost sm" onClick={() => quick(today(now))}>{t('historyFilter.today')}</button>
        <button className="btn ghost sm" onClick={() => quick(lastDays(now, LAST_DAYS))}>{t('historyFilter.last7')}</button>
        <div className="grow" />
        <button className="btn ghost sm" onClick={() => quick({ from: null, to: null })}>{t('historyFilter.clear')}</button>
      </div>
    </div>
  );
}
