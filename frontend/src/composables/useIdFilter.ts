// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { ref, watch, type Ref } from 'vue'

function loadInitial(storageKey: string): string[] {
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}

// One module-level ref per storage key (not per-component), so every view sharing the
// same filter composable reads/writes the same selection — picking a filter on one page
// keeps it applied on others. Registry keyed by storageKey so each filter (product,
// stage, workload, ...) gets its own independent, persisted selection.
const registry = new Map<string, Ref<string[]>>()

function selectedIdsFor(storageKey: string): Ref<string[]> {
  let selectedIds = registry.get(storageKey)
  if (!selectedIds) {
    selectedIds = ref<string[]>(loadInitial(storageKey))
    watch(
      selectedIds,
      (ids) => {
        try {
          localStorage.setItem(storageKey, JSON.stringify(ids))
        } catch {
          // localStorage unavailable (private browsing, quota, ...) — filter just won't persist.
        }
      },
      { deep: true },
    )
    registry.set(storageKey, selectedIds)
  }
  return selectedIds
}

export function useIdFilter(storageKey: string) {
  const selectedIds = selectedIdsFor(storageKey)

  function clear() {
    selectedIds.value = []
  }

  function matches(id: string): boolean {
    return selectedIds.value.length === 0 || selectedIds.value.includes(id)
  }

  return { selectedIds, clear, matches }
}
