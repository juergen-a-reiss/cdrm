// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { onMounted, ref } from 'vue'
import { ApiError } from '../api/http'

export function useResourceList<T>(fetcher: () => Promise<T[]>) {
  const items = ref<T[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function reload() {
    loading.value = true
    error.value = null
    try {
      items.value = await fetcher()
    } catch (e) {
      error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load data'
    } finally {
      loading.value = false
    }
  }

  onMounted(reload)

  return { items, loading, error, reload }
}
