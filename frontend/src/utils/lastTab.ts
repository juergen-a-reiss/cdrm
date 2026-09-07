// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

// Per-user (keyed by OIDC subject, not shared across accounts on the same browser)
// memory of which nav tab was open last, so HomeView can return the user to it.
const STORAGE_PREFIX = 'cdrm.lastTab.'

export function rememberLastTab(userId: string, key: string): void {
  try {
    localStorage.setItem(STORAGE_PREFIX + userId, key)
  } catch {
    // Storage unavailable (private browsing, quota) — losing the memory is harmless.
  }
}

export function getLastTab(userId: string): string | null {
  try {
    return localStorage.getItem(STORAGE_PREFIX + userId)
  } catch {
    return null
  }
}
