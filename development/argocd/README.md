# ArgoCD GitOps Demo

Demonstrates cdrm's GitOps support (see the root README's "Many companies already use
GitOps..." section) end to end in the local dev environment: the paris pipeline's
namespaces (`p-*`, per `seed/data.yaml`'s `k8s_namespaces` entries with
`use_git_ops: true`) are managed by [ArgoCD](https://argo-cd.readthedocs.io/) instead of
cdrm's direct Kubernetes patch — cdrm/seed.py pushes a commit, ArgoCD reconciles the
cluster from it. The frankfurt pipeline's `f-*` namespaces are untouched by any of this
and keep working exactly as before (seed.py `kubectl apply`s them directly).

## Pieces

- **`gitea` service** (`development/docker-compose.yaml`, add `gitea` to `components`) —
  a local git server hosting the demo repo. Nothing here needs a real GitHub/GitLab
  account.
- **`install.yaml`** — ArgoCD's official install manifest, vendored so setup doesn't
  depend on internet access at apply time (`curl -fsSL
  https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml` to
  refresh it).
- **`generate-applications.py`** — derives ArgoCD `Application` resources straight from
  `seed/data.yaml`, so the same file that drives cdrm's own `K8sGitopsConfig` is the only
  place this is configured. Only generates one for a namespace that actually has a
  workload in it (see its `namespaces_with_content()`) — an empty namespace has nothing
  in the repo to sync against.
- **`setup-gitops-repo.sh`** — bootstraps Gitea: a `cdrm` user, the `gitops-demo` repo,
  and a `release` branch (the paris pipeline's production namespaces commit there
  instead of `main` — see `k8s_namespaces` entries' `git_branch` overrides).
- **`setup-argocd.sh`** — installs ArgoCD into minikube and applies the generated
  Applications.
- **`portforward.sh`** — exposes the ArgoCD UI on **https://localhost:1961** (mirrors how
  `minikube dashboard --port=1964` is used elsewhere in this dev setup).

## Setup

```bash
# 1. Start Gitea (add it to development/components first, or pass it explicitly):
./up.sh
argocd/setup-gitops-repo.sh

# 2. Start minikube if it isn't already, then install ArgoCD:
minikube start
argocd/setup-argocd.sh

# 3. Seed as usual — bootstrap_kubernetes_objects() now pushes the p-* namespaces'
#    manifests to Gitea instead of kubectl-applying them; ArgoCD picks them up from there.
./seed.py --token <a cdrm-devops token>

# 4. Watch it sync:
argocd/portforward.sh   # https://localhost:1961, user "admin", password:
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

Re-running `argocd/setup-argocd.sh` after changing `seed/data.yaml`'s `k8s_namespaces`
re-applies the regenerated Applications — safe and idempotent.

## Why `host.minikube.internal`

ArgoCD's pods run inside minikube, not on the host — they can't resolve `localhost` as
Gitea (that's the *host's* loopback address, which is what `seed/data.yaml`'s
`git_repo` uses since `seed.py` and cdrm's own `bootRun` process both run on the host).
`generate-applications.py` substitutes minikube's own documented
`host.minikube.internal` hostname when emitting `Application.spec.source.repoURL`, so
ArgoCD reaches the same Gitea instance without joining minikube to `shared_net` or any
other network surgery.

## Resetting

`../seed.py --reset` deletes the ArgoCD Applications for GitOps-managed namespaces
first (best-effort — silently skipped if ArgoCD was never installed), then the
namespaces themselves, same as any other bootstrapped namespace. It does not touch the
Gitea repo's content or ArgoCD's own installation — re-run `setup-gitops-repo.sh` /
`setup-argocd.sh` if you want those reset too (`docker compose down -v` wipes Gitea's
volume; `kubectl delete namespace argocd` removes the ArgoCD install).
