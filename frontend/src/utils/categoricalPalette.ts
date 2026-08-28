// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

// Validated categorical palette (see the dataviz skill's palette.md) — fixed
// order, never cycled for a known/fixed set of categories (e.g. action types,
// which take slots 0-3 explicitly in releaseHistoryAction.ts).
export const CATEGORICAL_PALETTE = [
  '#2a78d6', // 1 blue
  '#eb6834', // 2 orange
  '#1baf7a', // 3 aqua
  '#eda100', // 4 yellow
  '#e87ba4', // 5 magenta
  '#008300', // 6 green
  '#4a3aa7', // 7 violet
  '#e34948', // 8 red
]

// For open-ended entity sets (products, workloads) there's no fixed category
// order to assign from, so color is derived deterministically from the entity's
// own id/name — the same entity keeps the same color across different filter
// selections instead of shifting when the visible set changes. This is a
// practical approximation, not a global registry: two different entities can
// land on the same hash slot once enough distinct ones appear together.
export function colorForKey(key: string): string {
  let hash = 0
  for (let i = 0; i < key.length; i++) {
    hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  }
  return CATEGORICAL_PALETTE[hash % CATEGORICAL_PALETTE.length]
}
