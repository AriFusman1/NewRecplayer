import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n';
import { formatUs, parseBoundUs, readDateTime, usToDay } from '../../util/time';
import { DateTimeField } from './DateTimeField';

interface Props {
  label: string;
  valueUs: number;
  /** End fields mean "through the end of that second". */
  isEnd?: boolean;
  onChange: (valueUs: number | null) => void;
  /** The recording: the calendar only offers its days. */
  boundsUs?: { startUs: number; endUs: number };
}

/**
 * DD/MM/YYYY HH:MM:SS (Israel time), typed or picked from a calendar and clock. While the text still equals the formatted value the exact
 * (sub-second) value is kept, so untouched defaults cover the whole recording.
 */
export function TimeField({ label, valueUs, isEnd, onChange, boundsUs }: Props) {
  const { t } = useI18n();
  const [text, setText] = useState(formatUs(valueUs));
  const [invalid, setInvalid] = useState(false);

  useEffect(() => {
    if (formatUs(valueUs) !== text) setText(formatUs(valueUs));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valueUs]);

  const commit = (typed: string) => {
    setText(typed);
    if (typed === formatUs(valueUs)) {
      setInvalid(false);
      return;
    }
    const parsedUs = parseBoundUs(typed, !!isEnd);
    setInvalid(parsedUs === null);
    onChange(parsedUs === null ? null : clampDateOnly(typed, parsedUs));
  };

  /**
   * A date alone means that day from its start (end field: to its end), but no earlier than the
   * recording starts and no later than it ends, so it never falls outside the recording by itself.
   */
  const clampDateOnly = (typed: string, parsedUs: number) => {
    if (!boundsUs || readDateTime(typed)?.time !== null) return parsedUs;
    return isEnd ? Math.min(parsedUs, boundsUs.endUs) : Math.max(parsedUs, boundsUs.startUs);
  };

  return (
    <div className="field">
      <label>{label}</label>
      <DateTimeField value={text} onChange={commit} invalid={invalid} placeholder="DD/MM/YYYY HH:MM:SS" requireTime
        dayTime={isEnd ? 'end' : 'start'} minDay={boundsUs && usToDay(boundsUs.startUs)} maxDay={boundsUs && usToDay(boundsUs.endUs)} />
      {invalid ? <span className="error-text">{t('err.INVALID_TIME')}</span> : <span className="hint">{t('upload.timeFormat')}</span>}
    </div>
  );
}
