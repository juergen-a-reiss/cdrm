// Runtime configuration (see src/config/runtimeConfig.ts). Intentionally empty for local
// development, where frontend/.env's VITE_* values apply. The Docker image replaces this
// file at container start with values from its OIDC_AUTHORITY/OIDC_CLIENT_ID env vars.
window.__CDRM_CONFIG__ = window.__CDRM_CONFIG__ || {}
