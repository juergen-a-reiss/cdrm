#!/bin/bash
# Exposes the ArgoCD UI/API on https://localhost:1961 (Ctrl-C to stop) — mirrors how
# `minikube dashboard --port=1964` is used elsewhere in this dev setup, since ArgoCD's
# server isn't reachable from the host any other way by default.

set -euo pipefail

echo "ArgoCD UI: https://localhost:1961 (self-signed cert — accept the browser warning)"
kubectl -n argocd port-forward svc/argocd-server 1961:443
