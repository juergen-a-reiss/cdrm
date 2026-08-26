#!/bin/bash

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
KEYCLOAK_CONTAINER="keycloak"
KEYCLOAK_URL="http://localhost:2305"

if ! docker ps --filter "name=^${KEYCLOAK_CONTAINER}$" --filter "status=running" --format "{{.Names}}" | grep -q "^${KEYCLOAK_CONTAINER}$"; then
  echo "Keycloak is not running. Start it first with: ./up.sh"
  exit 1
fi

BLACKLIST_DIR="${DIR}/src/keycloak-blacklist"
BLACKLIST_FILE="${BLACKLIST_DIR}/blacklist.txt"
BLACKLIST_URL="https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt"

if [ ! -f "${BLACKLIST_FILE}" ]; then
  echo "Downloading password blacklist..."
  mkdir -p "${BLACKLIST_DIR}"
  curl -fsSL "${BLACKLIST_URL}" -o "${BLACKLIST_FILE}"
  echo "Blacklist downloaded ($(wc -l < "${BLACKLIST_FILE}") entries)."
fi

echo "Waiting for Keycloak to be ready..."
until curl -sf "${KEYCLOAK_URL}/realms/master" > /dev/null 2>&1; do
  printf "."
  sleep 3
done
echo " ready."

echo "Running Ansible configuration..."
docker run --rm \
  --network shared_net \
  -v "${DIR}/ansible:/ansible" \
  -e ANSIBLE_CONFIG=/ansible/ansible.cfg \
  alpine/ansible \
  sh -c "ansible-playbook /ansible/playbooks/configure-keycloak-cdrm.yml"

echo "Done."

echo "Login to keycloak now with: http://localhost:2305/admin/master/console/#/platform/realm-settings and user admin/admin"

