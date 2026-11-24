/** Shared math for the bar graphs. */

/** Where `clientX` falls across `el`, from 0 (left edge) to 1 (right edge). */
export function fractionAt(element: HTMLElement, clientX: number): number {
  const bounds = element.getBoundingClientRect();
  return Math.min(1, Math.max(0, (clientX - bounds.left) / Math.max(1, bounds.width)));
}

/** CSS height of one bar: a 2px stub for empty bins, otherwise at least `minPct` of the tallest. */
export function barHeight(count: number, max: number, minPct = 4): string | number {
  return count === 0 ? 2 : `${Math.max(minPct, (count / Math.max(1, max)) * 100)}%`;
}

export function maxOf(values: number[]): number {
  let max = 1;
  for (const value of values) if (value > max) max = value;
  return max;
}
