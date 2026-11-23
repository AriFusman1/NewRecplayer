/** The list with `item` removed if present, appended otherwise. */
export function toggleIn<T>(list: T[], item: T): T[] {
  return list.includes(item) ? list.filter((existing) => existing !== item) : [...list, item];
}
