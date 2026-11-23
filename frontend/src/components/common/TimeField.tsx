import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n';
import { formatUs, msToUs, parseMs } from '../../util/time';

interface Props {
  label: string;
  valueUs: number;
  /** End fields mean "through the end of that second". */
  isEnd?: boolean;
  onChange: (valueUs: number | null) => void;
}

/**
 * DD/MM/YYYY HH:MM:SS (Israel time). While the text still equals the formatted value the exact
 * (sub-second) value is kept, so untouched defaults cover the whole recording.
 */
export function TimeField({ label, valueUs, isEnd, onChange }: Props) {
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
    const parsedMs = parseMs(typed);
    setInvalid(parsedMs === null);
    onChange(parsedMs === null ? null : msToUs(parsedMs, isEnd));
  };

  return (
    <div className="field">
      <label>{label}</label>
      <input className={`input time${invalid ? ' invalid' : ''}`} value={text} spellCheck={false}
        placeholder="DD/MM/YYYY HH:MM:SS" onChange={(e) => commit(e.target.value)} />
      {invalid ? <span className="error-text">{t('err.INVALID_TIME')}</span> : <span className="hint">{t('upload.timeFormat')}</span>}
    </div>
  );
}
