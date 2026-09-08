// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { computed } from 'vue'
import { authenticatedUser } from './authService'
import { accessTokenClaims, claimAsStringList } from './accessTokenClaims'
import { currentRoles } from './roles'

// One "action: stage, stage" entry of the cdrm-release-actions claim, parsed — mirrors
// the backend's own RebacContext.parseEntry() exactly (same "first colon splits action
// from stages" grammar, same case-insensitive action name), so the explanation shown
// here never drifts from what the backend actually enforces. A raw entry that doesn't
// parse (no colon) is dropped, same as the backend logging-and-skipping it.
export interface ReleaseActionGrant {
  action: string
  stages: string[]
}

function parseReleaseActionEntry(entry: string): ReleaseActionGrant | null {
  const separator = entry.indexOf(':')
  if (separator < 0) return null
  const action = entry.slice(0, separator).trim().toLowerCase()
  const stages = entry
    .slice(separator + 1)
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  return { action, stages }
}

export interface UserProfileInfo {
  username: string | undefined
  firstName: string | undefined
  lastName: string | undefined
  email: string | undefined
  // Every cdrm-* role the caller holds (currentRoles is already scoped to this client's
  // roles, but filtered again here defensively in case a non-cdrm client role is ever
  // added to the same client).
  roles: string[]
  // Raw claim values — null means "not set" (ReBAC doesn't apply for this attribute),
  // never "deny", per RebacContext's own doc comments.
  allowedProducts: string[] | null
  allowedWorkloads: string[] | null
  releaseActionsRaw: string[] | null
  releaseActions: ReleaseActionGrant[] | null
}

export const userProfile = computed<UserProfileInfo>(() => {
  const claims = accessTokenClaims.value
  const releaseActionsRaw = claimAsStringList(claims, 'cdrm-release-actions')
  return {
    username: (claims.preferred_username as string | undefined) ?? authenticatedUser.value?.profile.preferred_username,
    firstName: claims.given_name as string | undefined,
    lastName: claims.family_name as string | undefined,
    email: claims.email as string | undefined,
    roles: currentRoles.value.filter((role) => role.startsWith('cdrm-')),
    allowedProducts: claimAsStringList(claims, 'cdrm-products'),
    allowedWorkloads: claimAsStringList(claims, 'cdrm-workloads'),
    releaseActionsRaw,
    releaseActions: releaseActionsRaw
      ? releaseActionsRaw
          .map(parseReleaseActionEntry)
          .filter((grant): grant is ReleaseActionGrant => grant !== null)
      : null,
  }
})
