// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { computed } from 'vue'
import { accessTokenClaims } from './accessTokenClaims'
import { oidcClientId } from '../config/runtimeConfig'

// Every cdrm client role the caller holds — exported for UserProfileMenu, which shows
// the raw list; everything else here just derives a yes/no permission from it.
export const currentRoles = computed<string[]>(() => {
  const resourceAccess = accessTokenClaims.value.resource_access as Record<string, { roles?: string[] }> | undefined
  return resourceAccess?.[oidcClientId]?.roles ?? []
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
