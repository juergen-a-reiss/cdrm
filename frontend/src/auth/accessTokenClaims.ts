// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { computed } from 'vue'
import { authenticatedUser } from './authService'

// Decodes the raw access token's own JWT payload — the same token the backend reads
// every claim from (SecurityConfig's resource_access roles, RebacContext's
// cdrm-products/cdrm-workloads/cdrm-release-actions, CurrentActorResolver's
// given_name/family_name/email) — rather than oidc-client-ts's decoded `user.profile`,
// which comes from the separate ID token and only carries whatever the "openid profile"
// scope maps (no custom cdrm-* claims, and no guarantee of email — that's a different
// scope cdrm doesn't request). Reading everything from the access token keeps whatever
// the frontend shows in sync with what the backend actually enforces.
function decode(accessToken: string): Record<string, unknown> {
  const payload = accessToken.split('.')[1] ?? ''
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
  return JSON.parse(atob(padded)) as Record<string, unknown>
}

export const accessTokenClaims = computed<Record<string, unknown>>(() => {
  const user = authenticatedUser.value
  if (!user) return {}
  try {
    return decode(user.access_token)
  } catch {
    return {}
  }
})

// cdrm-products/cdrm-workloads/cdrm-release-actions are configured in Keycloak as
// multivalued claims (a JSON array in the token) — normalized defensively here in case a
// particular deployment's mapper is set up single-valued instead. null means the claim
// is absent (see RebacContext's own doc comments: "not set" is "ReBAC doesn't apply",
// never "deny").
export function claimAsStringList(claims: Record<string, unknown>, name: string): string[] | null {
  const value = claims[name]
  if (value == null) return null
  if (Array.isArray(value)) return value.map(String)
  return [String(value)]
}
