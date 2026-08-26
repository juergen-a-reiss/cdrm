// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { computed, ref } from 'vue'
import { UserManager, type User } from 'oidc-client-ts'
import { oidcConfig } from './oidcConfig'

const userManager = new UserManager(oidcConfig)

const currentUser = ref<User | null>(null)

userManager.events.addUserLoaded((user) => {
  currentUser.value = user
})
userManager.events.addUserUnloaded(() => {
  currentUser.value = null
})
userManager.events.addSilentRenewError((error) => {
  console.error('Silent token renewal failed', error)
})

export const isAuthenticated = computed(() => currentUser.value !== null && !currentUser.value.expired)
export const authenticatedUser = computed(() => currentUser.value)

export async function restoreSession(): Promise<void> {
  const user = await userManager.getUser()
  currentUser.value = user && !user.expired ? user : null
}

export function login(): Promise<void> {
  return userManager.signinRedirect({ state: { returnTo: window.location.pathname } })
}

export function logout(): Promise<void> {
  return userManager.signoutRedirect()
}

export async function handleLoginCallback(): Promise<void> {
  const user = await userManager.signinRedirectCallback()
  currentUser.value = user
  const returnTo = (user.state as { returnTo?: string } | null)?.returnTo ?? '/'
  window.history.replaceState({}, document.title, returnTo)
}

export function handleSilentRenewCallback(): Promise<void> {
  return userManager.signinSilentCallback()
}

export async function getAccessToken(): Promise<string | null> {
  const user = currentUser.value ?? (await userManager.getUser())
  if (!user || user.expired) {
    return null
  }
  return user.access_token
}
