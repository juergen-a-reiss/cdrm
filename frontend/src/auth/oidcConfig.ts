// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'
import { oidcAuthority, oidcClientId } from '../config/runtimeConfig'

export const oidcConfig: UserManagerSettings = {
  authority: oidcAuthority,
  client_id: oidcClientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  silent_redirect_uri: `${window.location.origin}/auth/silent-renew`,
  post_logout_redirect_uri: window.location.origin,
  response_type: 'code',
  scope: 'openid profile',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
}
