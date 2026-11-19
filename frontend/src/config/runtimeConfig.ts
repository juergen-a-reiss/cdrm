// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

// Settings that differ per installation, read at runtime instead of being compiled into
// the bundle — one published frontend image has to work against any Keycloak.
//
// /config.js (loaded by index.html before the app) sets window.__CDRM_CONFIG__. In the
// Docker image it's generated at container start from the OIDC_AUTHORITY/OIDC_CLIENT_ID
// env vars (see docker-entrypoint.d/40-cdrm-config.sh); in `npm run dev` it's the empty
// public/config.js, so the VITE_* values from frontend/.env apply instead.

interface CdrmRuntimeConfig {
  oidcAuthority?: string
  oidcClientId?: string
}

declare global {
  interface Window {
    __CDRM_CONFIG__?: CdrmRuntimeConfig
  }
}

const runtime = window.__CDRM_CONFIG__ ?? {}

export const oidcAuthority: string = runtime.oidcAuthority || import.meta.env.VITE_OIDC_AUTHORITY
export const oidcClientId: string = runtime.oidcClientId || import.meta.env.VITE_OIDC_CLIENT_ID || 'cdrm'
