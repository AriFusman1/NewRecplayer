/** Catalog names are unique ignoring case and surrounding spaces, as on the server. */
export function sameName(left: string, right: string): boolean {
  return left.trim().toLocaleLowerCase() === right.trim().toLocaleLowerCase();
}

/** The item whose name matches `name` (see {@link sameName}), if any. */
export function findByName<T extends { name: string }>(items: T[], name: string): T | undefined {
  return items.find((item) => sameName(item.name, name));
}
