interface Props {
  histogram: number[];
  binUs: number;
  durationUs: number;
  /** Playback position from range start; omit for a static graph. */
  positionUs?: number;
  height?: number;
}

/** Messages per time slice; bars light up as playback passes them, with a moving playhead. */
export function BarGraph({ histogram, binUs, durationUs, positionUs, height = 110 }: Props) {
  const max = Math.max(1, ...histogram);
  const playing = positionUs !== undefined;
  const pct = playing && durationUs > 0 ? Math.min(100, (positionUs! / durationUs) * 100) : 0;
  return (
    <div className="bars-wrap">
      <div className="bars" style={{ height }}>
        {histogram.map((count, i) => {
          const start = i * binUs;
          const end = start + binUs;
          const lit = playing && positionUs! >= end;
          const partial = playing && !lit && positionUs! > start ? (positionUs! - start) / binUs : 0;
          const h = count === 0 ? 2 : Math.max(4, (count / max) * 100);
          return (
            <div key={i} className={`bar${lit ? ' lit' : ''}${count === 0 ? ' empty-bin' : ''}`}
              style={{ height: count === 0 ? 2 : `${h}%` }} title={`${count}`}>
              {partial > 0 && count > 0 && <div className="fill" style={{ width: `${partial * 100}%` }} />}
            </div>
          );
        })}
        {playing && <div className="playhead" style={{ left: `calc(${pct}% - 1px)` }} />}
      </div>
    </div>
  );
}

/** Tiny static version for record cards. */
export function MiniBars({ histogram }: { histogram: number[] }) {
  const max = Math.max(1, ...histogram);
  return (
    <div className="mini-bars" style={{ direction: 'ltr' }}>
      {histogram.map((c, i) => <span key={i} style={{ height: `${c === 0 ? 4 : Math.max(10, (c / max) * 100)}%`, opacity: c === 0 ? 0.25 : 1 }} />)}
    </div>
  );
}
