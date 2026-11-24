import { useState, type KeyboardEvent } from 'react';
import { useI18n } from '../../i18n';
import { clockParts } from '../../util/time';
import { Icon } from '../common/Icon';

/** What one press of an arrow moves, per part of HH:MM:SS.mmm, in µs. */
const STEP_US = [3_600_000_000, 60_000_000, 1_000_000, 1_000];
const SEPARATORS = [':', ':', '.'];
const MILLIS = 3;

interface Props {
  label: string;
  valueUs: number;
  /** The value never goes below / above these (the recording, and the other end of the cut). */
  minUs: number;
  maxUs: number;
  onChange: (valueUs: number) => void;
}

/**
 * One end of a time cut as HH:MM:SS.mmm (Israel time) with up/down arrows. Clicking a part (hours,
 * minutes, seconds, milliseconds) chooses what the arrows move; milliseconds by default. The arrow
 * keys work too: up/down move, left/right choose the part.
 */
export function TimeStepper({ label, valueUs, minUs, maxUs, onChange }: Props) {
  const { t } = useI18n();
  const [part, setPart] = useState(MILLIS);
  const parts = clockParts(valueUs);

  const step = (direction: 1 | -1) => onChange(Math.min(maxUs, Math.max(minUs, valueUs + direction * STEP_US[part])));

  const onKeyDown = (e: KeyboardEvent) => {
    const keys: Record<string, () => void> = {
      ArrowUp: () => step(1),
      ArrowDown: () => step(-1),
      ArrowLeft: () => setPart((current) => Math.max(0, current - 1)),
      ArrowRight: () => setPart((current) => Math.min(MILLIS, current + 1)),
    };
    const action = keys[e.key];

    if (action) {
      e.preventDefault();
      e.stopPropagation(); // the graph and the dialog keep their own keys
      action();
    }
  };

  return (
    <div className="time-stepper" title={t('graph.partHint')}>
      <span className="ts-label">{label}</span>
      <span className="ts-value" role="spinbutton" tabIndex={0} aria-label={label} aria-valuetext={`${parts.slice(0, 3).join(':')}.${parts[3]}`}
        onKeyDown={onKeyDown}>
        {parts.map((text, i) => (
          <span key={i}>
            <span className={`ts-part${part === i ? ' on' : ''}`} onClick={() => setPart(i)}>{text}</span>
            {i < MILLIS && SEPARATORS[i]}
          </span>
        ))}
      </span>
      <span className="ts-arrows">
        <button type="button" aria-label={`${label}: ${t('graph.later')}`} disabled={valueUs >= maxUs} onClick={() => step(1)}>
          <Icon name="chevronUp" size={13} /></button>
        <button type="button" aria-label={`${label}: ${t('graph.earlier')}`} disabled={valueUs <= minUs} onClick={() => step(-1)}>
          <Icon name="chevronDown" size={13} /></button>
      </span>
    </div>
  );
}
