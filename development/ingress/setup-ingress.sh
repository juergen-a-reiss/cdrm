#!/bin/bash
# Exposes everything in minikube that has a UI via the minikube "ingress" addon (nginx),
# replacing the old port-forwards (argocd/portforward.sh on 1961, `minikube dashboard
# --port=1964`):
#
#   https://argocd.<domain>     ArgoCD UI
#   https://dashboard.<domain>  Kubernetes Dashboard
#
# <domain> defaults to "<minikube ip>.nip.io" (public wildcard DNS, nothing to configure).
# To use your own names instead, set DEV_DOMAIN in development/local.env (git-ignored,
# see local.env.example), e.g. DEV_DOMAIN=juergenareiss.dev — this script then prints the
# /etc/hosts lines to add, since a hosts file can't do wildcards.
#
# TLS: ingress-nginx terminates TLS with a wildcard cert for *.<domain>, signed by a
# local dev CA this script creates once in development/ingress/certs/ (git-ignored).
# Import certs/dev-ca.crt into your browser once to get rid of the warning — for a .dev
# domain that's mandatory, not optional: the whole .dev TLD is on the browsers' HSTS
# preload list, so they refuse plain HTTP and won't let you click through an untrusted
# certificate either.
#
# Idempotent — safe to re-run (e.g. after changing DEV_DOMAIN or a new `minikube start`).

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${DIR}/certs"
LOCAL_ENV="${DIR}/../local.env"

if [ -f "${LOCAL_ENV}" ]; then
  # shellcheck source=/dev/null
  source "${LOCAL_ENV}"
fi

if [ "$(kubectl config current-context 2>/dev/null)" != "minikube" ]; then
  echo "Error: kubectl's current context must be 'minikube'. Run 'minikube start' first." >&2
  exit 1
fi

MINIKUBE_IP="$(minikube ip)"
DOMAIN="${DEV_DOMAIN:-${MINIKUBE_IP}.nip.io}"
HOSTS=("argocd.${DOMAIN}" "dashboard.${DOMAIN}")

echo "Enabling minikube ingress + dashboard addons (no-op if already enabled)..."
minikube addons enable ingress
minikube addons enable dashboard
kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s

# --- Certificates -------------------------------------------------------------------
mkdir -p "${CERT_DIR}"
CA_KEY="${CERT_DIR}/dev-ca.key"
CA_CRT="${CERT_DIR}/dev-ca.crt"
TLS_KEY="${CERT_DIR}/${DOMAIN}.key"
TLS_CRT="${CERT_DIR}/${DOMAIN}.crt"

if [ ! -f "${CA_CRT}" ]; then
  echo "Creating local dev CA (${CA_CRT})..."
  openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
    -keyout "${CA_KEY}" -out "${CA_CRT}" \
    -subj "/CN=cdrm local dev CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" 2> /dev/null
fi

if [ ! -f "${TLS_CRT}" ]; then
  echo "Issuing wildcard certificate for *.${DOMAIN}..."
  openssl req -newkey rsa:2048 -nodes -keyout "${TLS_KEY}" -out "${CERT_DIR}/${DOMAIN}.csr" \
    -subj "/CN=*.${DOMAIN}" 2> /dev/null
  # 825 days: the maximum browsers (Safari/Chrome) still accept for a leaf certificate.
  openssl x509 -req -in "${CERT_DIR}/${DOMAIN}.csr" -CA "${CA_CRT}" -CAkey "${CA_KEY}" \
    -CAcreateserial -out "${TLS_CRT}" -days 825 -sha256 \
    -extfile <(printf "subjectAltName=DNS:*.%s,DNS:%s\nextendedKeyUsage=serverAuth\n" "${DOMAIN}" "${DOMAIN}") 2> /dev/null
  rm -f "${CERT_DIR}/${DOMAIN}.csr"
fi

# Ingress can only reference a TLS secret in its own namespace.
for ns in argocd kubernetes-dashboard; do
  kubectl -n "${ns}" create secret tls dev-ingress-tls --cert="${TLS_CRT}" --key="${TLS_KEY}" \
    --dry-run=client -o yaml | kubectl apply -f -
done

# --- Ingresses ----------------------------------------------------------------------
echo "Applying Ingresses for ${DOMAIN}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-server
  namespace: argocd
  annotations:
    # argocd-server only speaks HTTPS (its own self-signed cert) — nginx re-encrypts to it
    # instead of reconfiguring ArgoCD with server.insecure.
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
    nginx.ingress.kubernetes.io/force-ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts: [argocd.${DOMAIN}]
      secretName: dev-ingress-tls
  rules:
    - host: argocd.${DOMAIN}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: argocd-server
                port:
                  number: 443
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: kubernetes-dashboard
  namespace: kubernetes-dashboard
  annotations:
    nginx.ingress.kubernetes.io/force-ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts: [dashboard.${DOMAIN}]
      secretName: dev-ingress-tls
  rules:
    - host: dashboard.${DOMAIN}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: kubernetes-dashboard
                port:
                  number: 80
EOF

echo
echo "Ingress ready:"
for host in "${HOSTS[@]}"; do
  echo "  https://${host}"
done

if [[ "${DOMAIN}" != *.nip.io ]]; then
  echo
  echo "${DOMAIN} isn't resolvable by public DNS — add this to /etc/hosts (re-check after a"
  echo "'minikube delete', the minikube IP can change):"
  echo
  echo "  ${MINIKUBE_IP} ${HOSTS[*]}"
fi

echo
echo "Browser trust (once): import ${CA_CRT} as a certificate authority"
echo "  Chrome/Chromium: Settings > Privacy and security > Security > Manage certificates > Authorities > Import"
echo "  Firefox:         Settings > Privacy & Security > Certificates > View Certificates > Authorities > Import"
