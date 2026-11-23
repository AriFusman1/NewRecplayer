import type { PcapRecord, Tag } from '../api/types';

/** Search text matches the record's name or description (case-insensitive); empty matches all. */
export function matchesQuery(record: PcapRecord, query: string): boolean {
  const needle = query.trim().toLowerCase();
  return !needle || record.name.toLowerCase().includes(needle) || record.description.toLowerCase().includes(needle);
}

/** Tags usable with a category: global ones plus that category's own. No category = all tags. */
export function tagsForCategory(tags: Tag[], categoryId: string): Tag[] {
  return tags.filter((tag) => !tag.categoryId || !categoryId || tag.categoryId === categoryId);
}
