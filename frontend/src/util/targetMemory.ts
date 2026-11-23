import type { ItemRequest, Mode } from '../api/types';
import { loadJson, loadPref, saveJson } from './prefs';
import { DEFAULT_PORT, initialHost } from './target';

/**
 * Remembers, in this browser, the targets you play to: the last target per record, and your
 * recently used IPs (offered as suggestions in every target field). Personal convenience only:
 * it is never sent to the server.
 */

interface Target {
  host: string | null;
  port: number;
}

const RECENT_HOSTS_KEY = 'recentHosts';
const LAST_TARGETS_KEY = 'lastTargets';
const LEGACY_LAST_HOST_KEY = 'lastHost';
const MAX_RECENT_HOSTS = 10;
const MAX_REMEMBERED_RECORDS = 300;

/** Most recently used first. */
export function recentHosts(): string[] {
  const hosts = loadJson<string[]>(RECENT_HOSTS_KEY, []);
  if (hosts.length > 0) return hosts;
  const legacy = loadPref(LEGACY_LAST_HOST_KEY); // the single "last host" kept by older versions
  return legacy ? [legacy] : [];
}

/** Where to aim a record by default: where it was last played, else your last IP and port. */
export function suggestTarget(recordId: string, mode: Mode): Target {
  const last = loadJson<Record<string, Target>>(LAST_TARGETS_KEY, {})[recordId];
  if (last) return { host: initialHost(mode, last.host ?? recentHosts()[0] ?? ''), port: last.port };
  return { host: initialHost(mode, recentHosts()[0] ?? ''), port: DEFAULT_PORT };
}

/** Call when items are played, so their targets are suggested next time. */
export function rememberTargets(items: ItemRequest[]): void {
  const last = loadJson<Record<string, Target>>(LAST_TARGETS_KEY, {});
  let hosts = recentHosts();
  for (const item of items) {
    const host = item.host?.trim() || null;
    delete last[item.recordId]; // re-insert so the most recently played records are kept longest
    last[item.recordId] = { host, port: item.port };
    if (host) hosts = [host, ...hosts.filter((recent) => recent !== host)];
  }
  const keep = Object.keys(last).slice(-MAX_REMEMBERED_RECORDS);
  saveJson(LAST_TARGETS_KEY, Object.fromEntries(keep.map((id) => [id, last[id]])));
  saveJson(RECENT_HOSTS_KEY, hosts.slice(0, MAX_RECENT_HOSTS));
}
