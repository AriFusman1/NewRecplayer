import { useMemo, useRef, useState, type PointerEvent } from 'react';
import { formatDuration } from '../../util/time';
import { barHeight, fractionAt, maxOf } from './graph';

interface Props {
  histogram: number[];
  binUs: number;
  durationUs: number;
  /** Playback position from range start; omit for a static graph. */
  positionUs?: number;
  height?: number;
  /** When set, clicking or dragging on the graph jumps playback to that point (µs from range start). */
  onSeek?: (positionUs: number) => void;
}

/** Messages per time slice; bars light up as playback passes them, with a moving playhead. */
export function BarGraph({ histogram, binUs, durationUs, positionUs, height = 110, onSeek }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  /** Where the pointer is while dragging to seek (µs), or null. */
  const [ghost, setGhost] = useState<number | null>(null);
  const max = useMemo(() => maxOf(histogram), [histogram]);
  const playing = positionUs !== undefined;
  const progressPercent = playing && durationUs > 0 ? Math.min(100, (positionUs! / durationUs) * 100) : 0;

  const usAt = (clientX: number) => Math.round(fractionAt(ref.current!, clientX) * durationUs);
  const seekable = !!onSeek && durationUs > 0;
  const handlers = seekable ? {
    onPointerDown: (e: PointerEvent<HTMLDivElement>) => {
      e.preventDefault();
      ref.current!.setPointerCapture(e.pointerId);
      setGhost(usAt(e.clientX));
    },
    onPointerMove: (e: PointerEvent<HTMLDivElement>) => {
      if (ghost !== null) setGhost(usAt(e.clientX));
    },
    onPointerUp: (e: PointerEvent<HTMLDivElement>) => {
      if (ghost === null) return;
      setGhost(null);
      onSeek!(usAt(e.clientX));
    },
    onPointerCancel: () => setGhost(null),
  } : {};
  const ghostPct = ghost !== null && durationUs > 0 ? (ghost / durationUs) * 100 : 0;

  return (
    <div className="bars-wrap">
      <div ref={ref} className={`bars${seekable ? ' seekable' : ''}${ghost !== null ? ' seeking' : ''}`} style={{ height }} {...handlers}>
        {histogram.map((count, i) => {
          const start = i * binUs;
          const end = start + binUs;
          const lit = playing && positionUs! >= end;
          const partial = playing && !lit && positionUs! > start ? (positionUs! - start) / binUs : 0;
          return (
            <div key={i} className={`bar${lit ? ' lit' : ''}${count === 0 ? ' empty-bin' : ''}`}
              style={{ height: barHeight(count, max) }} title={seekable ? undefined : `${count}`}>
              {partial > 0 && count > 0 && <div className="fill" style={{ width: `${partial * 100}%` }} />}
            </div>
          );
        })}
        {playing && <div className="playhead" style={{ left: `calc(${progressPercent}% - 1px)` }} />}
        {ghost !== null && (
          <>
            <div className="seek-ghost" style={{ left: `calc(${ghostPct}% - 1px)` }} />
            <div className="seek-tip" style={{ left: `${Math.min(92, Math.max(8, ghostPct))}%` }}>{formatDuration(ghost)}</div>
          </>
        )}
      </div>
    </div>
  );
}

/** Tiny static version for record cards. */
export function MiniBars({ histogram }: { histogram: number[] }) {
  const max = maxOf(histogram);
  return (
    <div className="mini-bars" style={{ direction: 'ltr' }}>
      {histogram.map((count, i) => <span key={i} style={{ height: `${count === 0 ? 4 : Math.max(10, (count / max) * 100)}%`, opacity: count === 0 ? 0.25 : 1 }} />)}
    </div>
  );
}
