#!/bin/sh
# Writes the frontend's runtime configuration (see src/config/runtimeConfig.ts) from env
# vars at container start — run automatically by the nginx image's entrypoint, which
# executes every *.sh in /docker-entrypoint.d/ before starting nginx.
#
#   OIDC_AUTHORITY  (required) OIDC issuer URL as the browser reaches it,
#                   e.g. https://keycloak.example.com/realms/cdrm
#   OIDC_CLIENT_ID  (optional, default "cdrm") public OIDC client of the frontend

set -eu

if [ -z "${OIDC_AUTHORITY:-}" ]; then
  echo "cdrm-frontend: OIDC_AUTHORITY is not set — login will not work" >&2
fi

# Minimal JS string escaping (backslash, double quote) — the values are operator-supplied
# config, but a stray quote shouldn't break the page.
escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

cat > /usr/share/nginx/html/config.js <<EOF
window.__CDRM_CONFIG__ = {
  oidcAuthority: "$(escape "${OIDC_AUTHORITY:-}")",
  oidcClientId: "$(escape "${OIDC_CLIENT_ID:-cdrm}")",
}
EOF
