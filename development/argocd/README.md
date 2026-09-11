# ArgoCD GitOps Demo

Demonstrates cdrm's GitOps support (see the root README's "Many companies already use
GitOps..." section) end to end in the local dev environment: the paris pipeline's
namespaces, per `seed/data.yaml`'s `k8s_namespaces` entries with `use_git_ops: true`, are
managed by [ArgoCD](https://argo-cd.readthedocs.io/) instead of cdrm's direct Kubernetes
patch — cdrm/seed.py pushes a commit, ArgoCD reconciles the cluster from it. The
frankfurt pipeline's namespaces are untouched by any of this and keep working exactly as
before (seed.py `kubectl apply`s them directly).

Two Gitea repos are provisioned, not one: `gitops-demo` is every GitOps-managed
namespace's default, and `paris-prod-website` overrides `git_repo` to `gitops-demo-2`
instead — demonstrating cdrm's per-namespace repository override (see the root README's
"GitOps Repository Configuration") with an actual second repo, not just plumbing that's
never exercised.

## Pieces

- **`gitea` service** (`development/docker-compose.yaml`, add `gitea` to `components`) —
  a local git server hosting the two demo repos. Nothing here needs a real GitHub/GitLab
  account.
- **`install.yaml`** — ArgoCD's official install manifest, vendored so setup doesn't
  depend on internet access at apply time (`curl -fsSL
  https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml` to
  refresh it).
- **`generate-applications.py`** — derives ArgoCD `Application` resources straight from
  `seed/data.yaml`, so the same file that drives cdrm's own `K8sGitopsConfig` is the only
  place this is configured. Only generates one for a namespace that actually has a
  workload in it (see its `namespace_context()`) — an empty namespace has nothing in a
  repo to sync against. Each Application's `repoURL` is that namespace's own `git_repo`
  override if it has one, else its cluster's default.
- **`setup-gitops-repo.sh`** — bootstraps Gitea: a `cdrm` user and both repos
  (`gitops-demo`, `gitops-demo-2`). Doesn't pre-create any branches — `seed.py` creates
  whichever branch a namespace's own `git_branch` (or its cluster's default) needs, the
  first time something is pushed to it.
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

# 3. Seed as usual — bootstrap_kubernetes_objects() now pushes the GitOps-managed
#    namespaces' manifests to Gitea instead of kubectl-applying them; ArgoCD picks them
#    up from there.
./seed.py --token <a cdrm-devops token>

# 4. Watch it sync:
argocd/portforward.sh   # https://localhost:1961, user "admin", password:
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

Re-running `argocd/setup-argocd.sh` after changing `seed/data.yaml`'s `k8s_namespaces`
re-applies the regenerated Applications — safe and idempotent. `seed.py` itself (not
just `setup-argocd.sh`) also (re-)applies them on every normal run, best-effort — see
"Resetting" below.

## Why `host.minikube.internal`

ArgoCD's pods run inside minikube, not on the host — they can't resolve `localhost` as
Gitea (that's the *host's* loopback address, which is what `seed/data.yaml`'s `git_repo`
entries use, cluster-wide and per-namespace alike, since `seed.py` and cdrm's own
`bootRun` process both run on the host). `generate-applications.py` substitutes
minikube's own documented `host.minikube.internal` hostname when emitting
`Application.spec.source.repoURL`, so ArgoCD reaches the same Gitea instance without
joining minikube to `shared_net` or any other network surgery — for either repo.

## Resetting

`../seed.py --reset` deletes the ArgoCD Applications for GitOps-managed namespaces
first (best-effort — silently skipped if ArgoCD was never installed), then the
namespaces themselves, same as any other bootstrapped namespace. It also removes the
seeded `environments/` content from both Gitea repos (whichever branches each actually
has it on — best-effort per repo, silently skipped if a repo isn't reachable). It does
not touch ArgoCD's own installation — re-run `setup-gitops-repo.sh` / `setup-argocd.sh`
if you want the repos/ArgoCD reset too (`docker compose down -v` wipes Gitea's volume;
`kubectl delete namespace argocd` removes the ArgoCD install).

A plain `../seed.py` afterward recreates the Applications again on its own
(`apply_argocd_applications()`, best-effort — same silent skip if ArgoCD isn't
installed) — you don't need to re-run `setup-argocd.sh` just to get back to a synced
state after a reset, only when `seed/data.yaml`'s `k8s_namespaces` themselves changed.
