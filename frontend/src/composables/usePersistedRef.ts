// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { ref, watch, type Ref } from 'vue'

function loadInitial<T>(storageKey: string, defaultValue: T): T {
  try {
    const raw = localStorage.getItem(storageKey)
    if (raw === null) return defaultValue
    return JSON.parse(raw) as T
  } catch {
    return defaultValue
  }
}

// One module-level ref per storage key (not per-component) — the same registry
// pattern useIdFilter uses — so every component sharing a key reads/writes the same
// persisted value.
const registry = new Map<string, Ref<unknown>>()

// A single value (as opposed to useIdFilter's multi-select arrays) persisted to
// localStorage under storageKey, surviving page reloads — e.g. a chart's "group by"
// or "range" setting, so it doesn't quietly reset back to its default.
export function usePersistedRef<T>(storageKey: string, defaultValue: T): Ref<T> {
  let stored = registry.get(storageKey) as Ref<T> | undefined
  if (!stored) {
    stored = ref(loadInitial(storageKey, defaultValue)) as Ref<T>
    watch(
      stored,
      (value) => {
        try {
          localStorage.setItem(storageKey, JSON.stringify(value))
        } catch {
          // localStorage unavailable (private browsing, quota, ...) — setting just won't persist.
        }
      },
      { deep: true },
    )
    registry.set(storageKey, stored)
  }
  return stored
}
