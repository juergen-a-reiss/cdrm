#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

# Runs directly on the host (unlike start.sh's containerized Keycloak flow) because
# minikube's docker driver puts the API server on its own docker network — a sibling
# container on shared_net can't reach it without extra network/cert-mount plumbing.
# Running on the host reuses the same kubeconfig/context the cdrm backend itself
# resolves "minikube" against.
if ! command -v ansible-playbook >/dev/null 2>&1; then
  echo "ansible-playbook not found. Install it first, e.g.:" >&2
  echo "  sudo apt install ansible python3-kubernetes" >&2
  echo "  ansible-galaxy collection install -r ${DIR}/ansible/requirements.yml" >&2
  exit 1
fi

if ! kubectl config get-contexts minikube >/dev/null 2>&1; then
  echo "No 'minikube' kubectl context found. Start minikube first: minikube start" >&2
  exit 1
fi

echo "Running Ansible configuration..."
ANSIBLE_CONFIG="${DIR}/ansible/ansible.cfg" ansible-playbook \
  -i "${DIR}/ansible/inventory/hosts.yml" \
  "${DIR}/ansible/playbooks/configure-minikube-namespaces.yml"

echo "Done."
