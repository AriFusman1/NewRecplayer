import { useRef, useState, type PointerEvent } from 'react';
import { useI18n } from '../../i18n';
import { formatDuration, formatUs } from '../../util/time';
import { barHeight, fractionAt, maxOf } from './graph';

interface Props {
  /** Capture start/end (epoch µs). */
  startUs: number;
  endUs: number;
  binUs: number;
  /** All packets per bucket. */
  total: number[];
  /** Packets of the chosen ports per bucket (same buckets), or null when nothing is chosen. */
  highlight: number[] | null;
  /** Current selection (epoch µs), null = whole capture. */
  selectedStartUs: number | null;
  selectedEndUs: number | null;
  onChange: (startUs: number, endUs: number) => void;
}

type Drag = { kind: 'new'; anchorUs: number; startX: number; moved: boolean } | { kind: 'start' | 'end' };

/** Packets over the whole capture; drag across it (or drag the edges) to choose the time range. */
export function RangeGraph({ startUs, endUs, binUs, total, highlight, selectedStartUs, selectedEndUs, onChange }: Props) {
  const { t } = useI18n();
  const ref = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [draft, setDraft] = useState<{ fromUs: number; toUs: number } | null>(null);

  const domainEnd = Math.max(endUs, startUs + total.length * binUs);
  const span = Math.max(1, domainEnd - startUs);
  const clamp = (timeUs: number) => Math.min(endUs, Math.max(startUs, timeUs));
  const toPercent = (timeUs: number) => ((timeUs - startUs) / span) * 100;
  const usAt = (clientX: number) => clamp(startUs + fractionAt(ref.current!, clientX) * span);

  const selectionStartUs = draft ? Math.min(draft.fromUs, draft.toUs) : selectedStartUs ?? startUs;
  const selectionEndUs = draft ? Math.max(draft.fromUs, draft.toUs) : selectedEndUs ?? endUs;
  const coversWholeCapture = selectionStartUs <= startUs && selectionEndUs >= endUs;

  const down = (e: PointerEvent<HTMLDivElement>, kind: 'new' | 'start' | 'end') => {
    e.preventDefault();
    e.stopPropagation();
    ref.current!.setPointerCapture(e.pointerId);
    if (kind === 'new') {
      const anchorUs = usAt(e.clientX);
      setDrag({ kind: 'new', anchorUs, startX: e.clientX, moved: false });
    } else {
      setDrag({ kind });
      setDraft({ fromUs: selectionStartUs, toUs: selectionEndUs });
    }
  };

  const move = (e: PointerEvent<HTMLDivElement>) => {
    if (!drag) return;
    const pointerUs = usAt(e.clientX);
    if (drag.kind === 'new') {
      if (!drag.moved && Math.abs(e.clientX - drag.startX) < 3) return;
      if (!drag.moved) setDrag({ ...drag, moved: true });
      setDraft({ fromUs: drag.anchorUs, toUs: pointerUs });
    } else {
      setDraft((current) => (current ? (drag.kind === 'start' ? { fromUs: pointerUs, toUs: current.toUs } : { fromUs: current.fromUs, toUs: pointerUs }) : current));
    }
  };

  const up = () => {
    if (draft && (drag?.kind !== 'new' || drag.moved)) {
      const selectedFrom = Math.min(draft.fromUs, draft.toUs);
      const selectedTo = Math.max(draft.fromUs, draft.toUs);
      if (selectedTo > selectedFrom) onChange(Math.round(selectedFrom), Math.round(selectedTo));
    }
    setDrag(null);
    setDraft(null);
  };

  const max = maxOf(total);
  const left = toPercent(selectionStartUs);
  const right = toPercent(selectionEndUs);

  return (
    <div className="range-graph">
      <div className="range-head">
        <span className="small muted">{t('upload.graph')}</span>
        <button className="btn ghost sm" disabled={coversWholeCapture} onClick={() => onChange(startUs, endUs)}>{t('upload.graphReset')}</button>
      </div>
      <div ref={ref} className="range-area" onPointerDown={(e) => down(e, 'new')} onPointerMove={move}
        onPointerUp={up} onPointerCancel={up}>
        <div className="range-bars">
          {total.map((count, i) => {
            const highlighted = highlight?.[i] ?? 0;
            return (
              <div key={i} className={`rbar${count === 0 ? ' empty' : ''}${highlight ? '' : ' plain'}`}
                style={{ height: barHeight(count, max) }}
                title={`${formatUs(startUs + i * binUs)} · ${count}`}>
                {highlight && highlighted > 0 && <div className="rbar-hl" style={{ height: `${Math.min(100, (highlighted / count) * 100)}%` }} />}
              </div>
            );
          })}
        </div>
        {!coversWholeCapture && (
          <>
            <div className="range-dim" style={{ left: 0, width: `${left}%` }} />
            <div className="range-dim" style={{ left: `${right}%`, right: 0 }} />
          </>
        )}
        <div className="range-sel" style={{ left: `${left}%`, width: `${Math.max(0, right - left)}%` }} />
        <div className="range-handle" style={{ left: `${left}%` }} onPointerDown={(e) => down(e, 'start')} />
        <div className="range-handle" style={{ left: `${right}%` }} onPointerDown={(e) => down(e, 'end')} />
      </div>
      <div className="range-axis">
        <span>{formatUs(selectionStartUs)}</span>
        <span className="muted">{formatDuration(selectionEndUs - selectionStartUs)}</span>
        <span>{formatUs(selectionEndUs)}</span>
      </div>
    </div>
  );
}

/** Dense per-bucket sum of the given endpoints' sparse series. */
export function sumSeries(length: number, series: { binFrom?: number; counts?: number[] }[]): number[] {
  const out = new Array<number>(length).fill(0);
  for (const endpointSeries of series) {
    if (!endpointSeries.counts) continue;
    const from = endpointSeries.binFrom ?? 0;
    endpointSeries.counts.forEach((count, offset) => {
      const i = from + offset;
      if (i >= 0 && i < length) out[i] += count;
    });
  }
  return out;
}
