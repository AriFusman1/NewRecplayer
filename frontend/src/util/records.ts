import type { PcapRecord } from '../api/types';

/** Search text matches the record's name or description (case-insensitive); empty matches all. */
export function matchesQuery(record: PcapRecord, query: string): boolean {
  const needle = query.trim().toLowerCase();
  return !needle || record.name.toLowerCase().includes(needle) || record.description.toLowerCase().includes(needle);
}
