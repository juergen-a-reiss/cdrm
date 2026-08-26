// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { computed } from 'vue'
import { authenticatedUser } from './authService'

interface AccessTokenClaims {
  resource_access?: Record<string, { roles?: string[] }>
}

function decodeAccessTokenClaims(accessToken: string): AccessTokenClaims {
  const payload = accessToken.split('.')[1] ?? ''
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
  return JSON.parse(atob(padded)) as AccessTokenClaims
}

const currentRoles = computed<string[]>(() => {
  const user = authenticatedUser.value
  if (!user) {
    return []
  }
  try {
    const claims = decodeAccessTokenClaims(user.access_token)
    const clientId = import.meta.env.VITE_OIDC_CLIENT_ID
    return claims.resource_access?.[clientId]?.roles ?? []
  } catch {
    return []
  }
})

function hasAnyRole(...roles: string[]): boolean {
  return roles.some((role) => currentRoles.value.includes(role))
}

export const isAdmin = computed(() => hasAnyRole('cdrm-devops'))
export const canManageProducts = computed(() => hasAnyRole('cdrm-devops', 'cdrm-productowner'))
export const canManageWorkloads = computed(() => hasAnyRole('cdrm-devops', 'cdrm-productowner', 'cdrm-developer'))
export const canManageReleases = computed(() => hasAnyRole('cdrm-devops', 'cdrm-productowner', 'cdrm-developer'))
export const canPromoteReleases = computed(() => hasAnyRole('cdrm-productowner'))
