#!/bin/bash

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
KEYCLOAK_CONTAINER="keycloak"
KEYCLOAK_URL="http://localhost:2305"

# This script backgrounds `minikube dashboard --port=1964` and `argocd/portforward.sh`
# (port 1961) below — neither dies with a `minikube delete`/lost shell, so a stale one
# from an earlier run collides with the new one on the same port. Kill any leftovers
# first (see purge.sh, which does the same before tearing everything else down).
pkill -f "minikube dashboard --port=1964" || true
lsof -ti:1964 -sTCP:LISTEN | xargs -r kill || true
pkill -f "argocd/portforward.sh" || true
lsof -ti:1961 -sTCP:LISTEN | xargs -r kill || true

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

minikube start

echo "Minikube started. Add the metrics add-on for dashboard"
minikube addons enable metrics-server

echo "Start Dashboard"
# In case the shell executing this script terminates, minikube dashboard UI might no longer be reachable.
minikube dashboard --port=1964 &

./argocd/setup-gitops-repo.sh
./argocd/setup-argocd.sh

# In case the shell executing this script terminates, argocd UI might no longer be reachable.
./argocd/portforward.sh &

echo "Done"
