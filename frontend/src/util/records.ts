import type { PcapRecord, Tag } from '../api/types';

/** Search text matches the record's name or description (case-insensitive); empty matches all. */
export function matchesQuery(record: PcapRecord, query: string): boolean {
  const needle = query.trim().toLowerCase();
  return !needle || record.name.toLowerCase().includes(needle) || record.description.toLowerCase().includes(needle);
}

/** A global tag, or one of this category's own. */
export function tagAllowedIn(tag: Tag, categoryId: string): boolean {
  return !tag.categoryId || tag.categoryId === categoryId;
}

/** Tags usable with a category: global ones plus that category's own. No category = all tags. */
export function tagsForCategory(tags: Tag[], categoryId: string): Tag[] {
  return tags.filter((tag) => !categoryId || tagAllowedIn(tag, categoryId));
}
