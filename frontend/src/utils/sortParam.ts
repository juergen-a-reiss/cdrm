// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import type { SortByItem } from '../components/ResourceTable.vue'

// Vuetify's sort-by state, as the "key,direction" query string every list endpoint
// accepts — sorting happens in the backend, not in the browser, so this is the only
// thing the frontend does with a sort-by change besides persisting and re-fetching.
export function sortParam(sortBy: SortByItem[]): string | undefined {
  const item = sortBy[0]
  if (!item) return undefined
  return `${item.key},${item.order ?? 'asc'}`
}
