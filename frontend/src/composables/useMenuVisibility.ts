// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { ref, watch } from 'vue'
import { isAuthenticated } from '../auth/authService'
import { menuVisibilityApi } from '../api/config'

// Module-level (not per-component) state, shared by every caller — same "one shared ref
// registry" approach as useToast — so App.vue's nav and any future consumer read the
// same fetch instead of each re-requesting it.
const visibleKeys = ref<Set<string>>(new Set())
// True once the current auth state's fetch (or non-fetch, if logged out) has settled —
// HomeView needs this to avoid picking a fallback tab while visibleKeys is still just
// its empty initial value.
const loaded = ref(false)

watch(
  isAuthenticated,
  async (authenticated) => {
    loaded.value = false
    if (!authenticated) {
      visibleKeys.value = new Set()
      loaded.value = true
      return
    }
    try {
      visibleKeys.value = new Set(await menuVisibilityApi.effective())
    } catch {
      // Fail closed: an unreadable menu-visibility response hides extra nav entries
      // rather than risking a role seeing something it shouldn't.
      visibleKeys.value = new Set()
    } finally {
      loaded.value = true
    }
  },
  { immediate: true },
)

export function useMenuVisibility() {
  return { visibleKeys, loaded }
}
