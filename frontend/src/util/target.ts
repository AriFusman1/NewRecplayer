import type { ItemRequest, Mode } from '../api/types';
import { MS_PER_SECOND } from './time';

/** Port suggested for a new target. */
export const DEFAULT_PORT = 5000;


export function portValid(port: number): boolean {
  return Number.isInteger(port) && port >= 1 && port <= 65535;
}

/** TCP_CLIENT only needs a port to listen on; the other modes also need the service's host. */
export function targetValid(mode: Mode, host: string | null, port: number): boolean {
  return mode === 'TCP_CLIENT' ? portValid(port) : portValid(port) && !!host && host.trim().length > 0;
}

/** ":5000" for a listening port, "10.0.0.5:5000" for a target service. */
export function formatTarget(mode: Mode, host: string | null, port: number): string {
  return mode === 'TCP_CLIENT' ? `:${port}` : `${host ?? ''}:${port}`;
}

/**
 * Identifies what a target occupies: the port we listen on (TCP_CLIENT), or the service's
 * host:port. Two items with the same key cannot play at the same time.
 */
export function targetKey(mode: Mode, host: string | null, port: number): string {
  return mode === 'TCP_CLIENT' ? `listen:${port}` : `${(host ?? '').trim().toLowerCase()}:${port}`;
}

/** A host field's initial value: none for TCP_CLIENT (we listen), otherwise `fallback`. */
export function initialHost(mode: Mode, fallback = ''): string | null {
  return mode === 'TCP_CLIENT' ? null : fallback;
}

/** What the server expects for one item: a trimmed host, or null when there is none. */
export function toItemRequest({ recordId, host, port, sleepAfterMs }: ItemRequest): ItemRequest {
  return { recordId, host: host?.trim() || null, port, sleepAfterMs };
}

/** "1.5" (seconds, as typed) → 1500 ms; empty or negative → 0. */
export function secondsToMs(text: string): number {
  return Math.max(0, Math.round(parseFloat(text || '0') * MS_PER_SECOND));
}

/** 1500 ms → 1.5, for a seconds input field. */
export function msToSeconds(ms: number): number {
  return ms / MS_PER_SECOND;
}

/** Whole seconds still to wait, rounded up so a countdown never shows 0 too early. */
export function secondsLeft(ms: number): number {
  return Math.ceil(ms / MS_PER_SECOND);
}
