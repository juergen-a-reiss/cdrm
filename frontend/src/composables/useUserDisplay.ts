// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { reactive } from 'vue'
import { usersApi } from '../api/users'

// Module-level (not per-component) — one shared cache for the whole app, the same
// "shared registry" pattern useToast/useMenuVisibility already use, since the same actor
// ids (e.g. a small team) recur across many views/rows. A reactive Map so any component
// reading displayName(id) re-renders automatically once its actual resolution lands —
// rows render with the raw id first, then swap in the resolved name a moment later.
const cache = reactive(new Map<string, string>())
const inFlight = new Set<string>()

export function useUserDisplay() {
  function displayName(id: string): string {
    return cache.get(id) ?? id
  }

  // Kicks off a lookup for whichever of the given ids aren't already cached or already
  // being fetched — call this once per load with every createdBy/modifiedBy id a view is
  // about to render. Best-effort: a failed lookup just leaves the raw ids showing rather
  // than surfacing an error for what's a purely cosmetic resolution.
  async function resolve(ids: (string | null | undefined)[]) {
    const missing = [...new Set(ids.filter((id): id is string => !!id))].filter(
      (id) => !cache.has(id) && !inFlight.has(id),
    )
    if (missing.length === 0) return
    missing.forEach((id) => inFlight.add(id))
    try {
      const result = await usersApi.displayNames(missing)
      for (const [id, name] of Object.entries(result)) {
        cache.set(id, name)
      }
    } catch {
      // Leave the raw ids showing.
    } finally {
      missing.forEach((id) => inFlight.delete(id))
    }
  }

  return { displayName, resolve }
}
