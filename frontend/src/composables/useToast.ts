// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { nextTick, ref } from 'vue'

// Module-level (not per-component) state, shared by every caller of useToast() and
// rendered once by ToastHost.vue mounted at the app root — the same "one shared ref
// registry" approach as useIdFilter.
const message = ref<string | null>(null)
const visible = ref(false)

export function useToast() {
  // If a toast is already showing, close it first so the snackbar's auto-dismiss timer
  // restarts for the new message instead of inheriting whatever was left of the old one.
  async function showToast(text: string) {
    if (visible.value) {
      visible.value = false
      await nextTick()
    }
    message.value = text
    visible.value = true
  }

  return { message, visible, showToast }
}
