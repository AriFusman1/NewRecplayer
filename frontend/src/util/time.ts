// All user-facing times are Israel time (Asia/Jerusalem), "DD/MM/YYYY HH:MM:SS".
// Internally everything is epoch microseconds; conversion happens only here.

const TZ = 'Asia/Jerusalem';
const fmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});

function parts(ms: number): Record<string, number> {
  const out: Record<string, number> = {};
  for (const p of fmt.formatToParts(new Date(ms))) if (p.type !== 'literal') out[p.type] = Number(p.value);
  return out;
}

const pad = (n: number) => String(n).padStart(2, '0');

export function formatMs(ms: number): string {
  const p = parts(ms);
  return `${pad(p.day)}/${pad(p.month)}/${p.year} ${pad(p.hour)}:${pad(p.minute)}:${pad(p.second)}`;
}

export function formatUs(us: number): string {
  return formatMs(Math.floor(us / 1000));
}

/** Offset of Israel time from UTC at the given instant, in ms. */
function offsetAt(ms: number): number {
  const p = parts(ms);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - Math.floor(ms / 1000) * 1000;
}

const RE = /^\s*(\d{1,2})\/(\d{1,2})\/(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})\s*$/;

/** Parses "DD/MM/YYYY HH:MM:SS" (Israel time) to epoch ms; null if invalid. */
export function parseMs(text: string): number | null {
  const m = RE.exec(text);
  if (!m) return null;
  const [d, mo, y, h, mi, s] = m.slice(1).map(Number);
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59) return null;
  const wall = Date.UTC(y, mo - 1, d, h, mi, s);
  let ms = wall - offsetAt(wall);
  ms = wall - offsetAt(ms); // second pass settles DST transitions
  const back = parts(ms);
  if (back.day !== d || back.month !== mo) return null; // e.g. 31/02
  return ms;
}

export function formatDuration(us: number): string {
  const total = Math.max(0, Math.floor(us / 1_000_000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`;
}

export function formatNumber(n: number): string {
  return n.toLocaleString('en-US');
}
