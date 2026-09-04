#!/usr/bin/env python3
"""Generates ArgoCD Application manifests for every GitOps-managed namespace in
seed/data.yaml — one source of truth for both cdrm's own K8sGitopsConfig (see
Cluster.kt) and the Applications that actually make ArgoCD sync those namespaces.

Prints YAML to stdout — pipe into `kubectl apply -f -` (see setup-argocd.sh).
"""

import re
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).parent.parent))
import seed  # noqa: E402 — needs sys.path set up first

DATA_FILE = Path(__file__).parent.parent / "seed" / "data.yaml"

# ArgoCD's pods run inside minikube and can't resolve the Gitea repo via "localhost"
# (that's the *host's* loopback, reachable from seed.py and cdrm's own bootRun process,
# not from inside the cluster) — host.minikube.internal is minikube's own documented
# hostname for "the machine minikube itself is running on", so it resolves the same
# Gitea instance from in-cluster without needing to join minikube to shared_net.
IN_CLUSTER_HOST = "host.minikube.internal"


def slugify(value: str) -> str:
    """Kubernetes resource names are DNS-1123 subdomains: lowercase alphanumerics and
    hyphens only."""
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", value.lower())).strip("-")


def namespace_context(data: dict) -> dict[str, dict]:
    """Effective namespace (stage namespace_prefix + workload kubernetes_namespace) ->
    {"stage": <the stage that produced it>, "workloads": [<every workload landing
    there>]} — the same resolution seed.py's push_gitops_manifests() uses. A namespace
    with no workload in it has nothing to sync (the repo path wouldn't even exist), so
    it's pointless — and would show as a permanently broken Application — to generate
    one for it; namespaces_with_content() callers should just check membership."""
    result: dict[str, dict] = {}
    stages = data.get("stages", [])
    for workload in data.get("workloads", []):
        if not workload.get("kubernetes"):
            continue
        base_namespace = workload["kubernetes_namespace"]
        for stage in seed.workload_stages(stages, workload):
            namespace = f"{stage.get('namespace_prefix') or ''}{base_namespace}"
            result.setdefault(namespace, {"stage": stage, "workloads": []})["workloads"].append(workload)
    return result


def application_name(namespace: str, stage: dict, workloads: list[dict]) -> str:
    """A namespace name alone (e.g. "p-qa-platform") doesn't read well in the ArgoCD UI —
    prefer whichever of these is actually specific to what's being synced there: the
    workload's own name when there's exactly one, or the shared product name when several
    workloads land in the same namespace and all belong to the same product (a namespace
    grouping one product's workloads together is the common case). Suffixed with the
    stage name either way, since the same workload/product can land in more than one
    namespace across stages (e.g. qa and production) — without it, those Applications
    would collide on name. Workloads from more than one *different* product sharing a
    namespace is the one case with no single accurate label; falls back to the namespace
    itself there."""
    products = {w["product"] for w in workloads}
    if len(workloads) == 1:
        label = workloads[0]["name"]
    elif len(products) == 1:
        label = next(iter(products))
    else:
        return slugify(namespace)
    return slugify(f"{label}-{stage['name']}")


def applications(data: dict) -> list[dict]:
    apps = []
    contexts = namespace_context(data)
    for cluster in data.get("clusters", []):
        gitops = cluster.get("gitops")
        if not gitops:
            continue
        repo_url = gitops["git_repo"].replace("localhost", IN_CLUSTER_HOST)
        default_branch = gitops.get("git_branch", "main")
        for entry in cluster.get("k8s_namespaces") or []:
            if not entry.get("use_git_ops"):
                continue
            namespace = entry["namespace"]
            context = contexts.get(namespace)
            if context is None:
                continue
            file_expression = entry.get("file_expression")
            if not file_expression:
                print(f"Error: namespace '{namespace}' has use_git_ops: true but no file_expression", file=sys.stderr)
                sys.exit(1)
            path = file_expression.replace("{namespace}", namespace).rsplit("/", 1)[0]
            apps.append(
                {
                    "apiVersion": "argoproj.io/v1alpha1",
                    "kind": "Application",
                    "metadata": {
                        "name": application_name(namespace, context["stage"], context["workloads"]),
                        "namespace": "argocd",
                    },
                    "spec": {
                        "project": "default",
                        "source": {
                            "repoURL": repo_url,
                            "targetRevision": entry.get("git_branch") or default_branch,
                            "path": path,
                        },
                        "destination": {
                            "server": "https://kubernetes.default.svc",
                            "namespace": namespace,
                        },
                        "syncPolicy": {
                            "automated": {"prune": True, "selfHeal": True},
                            "syncOptions": ["CreateNamespace=true"],
                        },
                    },
                }
            )
    return apps


def main() -> None:
    data = yaml.safe_load(DATA_FILE.read_text())
    apps = applications(data)
    if not apps:
        print("Error: no GitOps-managed namespaces found in data.yaml", file=sys.stderr)
        sys.exit(1)
    print(yaml.safe_dump_all(apps, sort_keys=False))


if __name__ == "__main__":
    main()
