#!/bin/bash
# Bootstraps the local Gitea instance (see docker-compose.yaml's gitea service) for the
# GitOps demo: a "cdrm" user, a "gitops-demo" repo, and a "release" branch (the paris
# pipeline's production namespaces, per seed/data.yaml, commit to "release" instead of
# the default "main" — see K8sGitopsConfig.gitBranch / K8sNamespaceGitopsConfig.gitBranch).
#
# Idempotent — safe to re-run. Requires gitea to be running (./up.sh, with gitea in
# development/components).

set -euo pipefail

GITEA_CONTAINER="gitea"
GITEA_URL="http://localhost:3000"
GITEA_USER="cdrm"
GITEA_PASSWORD="cdrm"
REPO_NAME="gitops-demo"

if ! docker ps --filter "name=^${GITEA_CONTAINER}$" --filter "status=running" --format "{{.Names}}" | grep -q "^${GITEA_CONTAINER}$"; then
  echo "Gitea is not running. Start it first with: ./up.sh (with 'gitea' listed in development/components)"
  exit 1
fi

echo "Waiting for Gitea to be ready..."
until curl -sf "${GITEA_URL}/api/healthz" > /dev/null 2>&1; do
  printf "."
  sleep 2
done
echo " ready."

if curl -sf -u "${GITEA_USER}:${GITEA_PASSWORD}" "${GITEA_URL}/api/v1/user" > /dev/null 2>&1; then
  echo "Gitea user '${GITEA_USER}' already exists."
else
  echo "Creating Gitea user '${GITEA_USER}'..."
  # --user git: the image's app process runs as "git", not root (docker exec defaults
  # to root, which gitea refuses to run as).
  docker exec --user git "${GITEA_CONTAINER}" gitea admin user create \
    --username "${GITEA_USER}" --password "${GITEA_PASSWORD}" --email "${GITEA_USER}@cdrm.local" \
    --admin --must-change-password=false
fi

if curl -sf -u "${GITEA_USER}:${GITEA_PASSWORD}" "${GITEA_URL}/api/v1/repos/${GITEA_USER}/${REPO_NAME}" > /dev/null 2>&1; then
  echo "Repo '${GITEA_USER}/${REPO_NAME}' already exists."
else
  echo "Creating repo '${GITEA_USER}/${REPO_NAME}'..."
  curl -sf -u "${GITEA_USER}:${GITEA_PASSWORD}" -X POST "${GITEA_URL}/api/v1/user/repos" \
    -H "Content-Type: application/json" \
    -d "{\"name\": \"${REPO_NAME}\", \"private\": false, \"auto_init\": true, \"default_branch\": \"main\"}" > /dev/null
fi

if curl -sf -u "${GITEA_USER}:${GITEA_PASSWORD}" "${GITEA_URL}/api/v1/repos/${GITEA_USER}/${REPO_NAME}/branches/release" > /dev/null 2>&1; then
  echo "Branch 'release' already exists."
else
  echo "Creating branch 'release' (from 'main')..."
  curl -sf -u "${GITEA_USER}:${GITEA_PASSWORD}" -X POST "${GITEA_URL}/api/v1/repos/${GITEA_USER}/${REPO_NAME}/branches" \
    -H "Content-Type: application/json" \
    -d '{"new_branch_name": "release", "old_branch_name": "main"}' > /dev/null
fi

echo "Done. Repo URL: ${GITEA_URL}/${GITEA_USER}/${REPO_NAME}.git (user ${GITEA_USER} / password ${GITEA_PASSWORD})"
