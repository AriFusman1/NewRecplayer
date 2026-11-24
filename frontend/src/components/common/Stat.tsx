/** One labelled number in a stats grid. */
export function Stat({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className={`value${mono ? ' mono' : ''}`}><span className="ltr">{value}</span></div>
    </div>
  );
}
