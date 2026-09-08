// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { computed } from 'vue'
import { accessTokenClaims } from './accessTokenClaims'

// Every cdrm client role the caller holds — exported for UserProfileMenu, which shows
// the raw list; everything else here just derives a yes/no permission from it.
export const currentRoles = computed<string[]>(() => {
  const clientId = import.meta.env.VITE_OIDC_CLIENT_ID
  const resourceAccess = accessTokenClaims.value.resource_access as Record<string, { roles?: string[] }> | undefined
  return resourceAccess?.[clientId]?.roles ?? []
})

function hasAnyRole(...roles: string[]): boolean {
  return roles.some((role) => currentRoles.value.includes(role))
}

export const isAdmin = computed(() => hasAnyRole('cdrm-devops'))
export const canManageProducts = computed(() => hasAnyRole('cdrm-devops', 'cdrm-productowner'))
export const canManageWorkloads = computed(() => hasAnyRole('cdrm-devops', 'cdrm-productowner', 'cdrm-developer'))
export const canManageReleases = computed(() => hasAnyRole('cdrm-devops', 'cdrm-productowner', 'cdrm-developer'))
export const canPromoteReleases = computed(() => hasAnyRole('cdrm-productowner'))
export const canRollbackReleases = computed(() => hasAnyRole('cdrm-productowner'))
export const canRedeployReleases = computed(() => hasAnyRole('cdrm-productowner'))
