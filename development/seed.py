#!/usr/bin/env python3
"""Seeds the cdrm backend with sample stages, products, workloads, and releases.

Intended for a freshly migrated, empty database — re-running against
already-seeded data will fail on unique-name constraints.

Also bootstraps the underlying Kubernetes Deployment/StatefulSet objects in
minikube for every kubernetes-managed workload, in each stage's namespace
(stage namespace_prefix + workload kubernetes_namespace) — cdrm only patches
an existing object's image on promotion, it doesn't create one. For a
GitOps-managed namespace (data.yaml's k8s_namespaces entries with
use_git_ops: true — currently the paris pipeline's "p-*" namespaces), the
manifest is instead pushed to the GitOps demo repo (see
development/argocd/README.md); ArgoCD creates the actual objects by syncing
from there, so this script never kubectl-applies anything into those
namespaces. Both steps are idempotent and safe to re-run on their own.

Pass --reset to instead wipe the database tables and delete those Kubernetes
objects, including their namespaces, so you can re-run a normal seed from a
clean slate. --reset does not need --token — it talks to Postgres and
minikube directly, not the API.
"""

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

import yaml

BOOTSTRAP_IMAGE = "nginx:1.20-alpine"

# Matches development/docker-compose.yaml's postgres service and cdrm's own
# datasource config (src/main/resources/application-dev.yaml).
DB_CONTAINER = "postgres"
DB_USER = "dockers"
DB_NAME = "cdrm"
DB_TABLES = ["release_history", "release", "workload_stage", "workload", "product_stage", "product", "stage"]

# Matches development/argocd/setup-gitops-repo.sh.
GITEA_USER = "cdrm"
GITEA_PASSWORD = "cdrm"
GITEA_REPO_URL = f"http://{GITEA_USER}:{GITEA_PASSWORD}@localhost:3000/{GITEA_USER}/gitops-demo.git"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--token", help="Bearer token for a user with the cdrm-devops role (required unless --reset)")
    parser.add_argument("--api-url", default="http://localhost:8080", help="Backend base URL")
    parser.add_argument(
        "--data", type=Path, default=Path(__file__).parent / "seed" / "data.yaml", help="Path to the YAML seed data file"
    )
    parser.add_argument(
        "--reset", action="store_true", help="Wipe the database and delete the bootstrapped Kubernetes objects, then exit"
    )
    args = parser.parse_args()
    if not args.reset and not args.token:
        parser.error("--token is required unless --reset is given")
    return args


def post(api_url: str, token: str, path: str, body: dict) -> dict:
    request = urllib.request.Request(
        f"{api_url}{path}",
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as e:
        print(f"Failed POST {path} (HTTP {e.code}): {e.read().decode()}", file=sys.stderr)
        sys.exit(1)

def cluster_namespace_names(cluster: dict) -> list[str]:
    """Every namespace name configured for one cluster's k8s_namespaces, in file order."""
    return [entry["namespace"] for entry in (cluster.get("k8s_namespaces") or [])]


def build_gitops_config(cluster: dict) -> dict | None:
    """cdrm's K8sGitopsConfig for a cluster — one K8sNamespaceGitopsConfig entry per
    k8s_namespaces entry with use_git_ops: true, carrying that namespace's own
    file_expression/yaml_expression and (optionally) its own git_branch override. A
    namespace without use_git_ops (or with it false) is left out of the map entirely, so
    it keeps deploying via cdrm's direct Kubernetes patch instead of a git commit.
    gitops.git_branch is the cluster-wide default branch; an entry's own git_branch
    overrides it for just that namespace (left null here otherwise)."""
    gitops = cluster.get("gitops")
    if not gitops:
        return None
    namespaces = {
        entry["namespace"]: {
            "namespace": entry["namespace"],
            "useGitOps": True,
            "fileExpression": entry.get("file_expression"),
            "yamlExpression": entry.get("yaml_expression"),
            "gitBranch": entry.get("git_branch"),
        }
        for entry in (cluster.get("k8s_namespaces") or [])
        if entry.get("use_git_ops")
    }
    return {
        "useGitOps": gitops.get("use_git_ops", True),
        "gitRepo": gitops.get("git_repo"),
        "gitBranch": gitops.get("git_branch", "main"),
        "namespaces": namespaces,
    }


def seed_clusters(api_url: str, token: str, clusters: list[dict]) -> dict[str, str]:
    print("Seeding clusters...")
    print(clusters)
    ids = {}
    for cluster in clusters:
        namespace_names = cluster_namespace_names(cluster)
        body = {
            "name": cluster["name"],
            "description": cluster.get("description"),
            "clusterType": cluster.get("cluster_type"),
            "url": cluster.get("url"),
            "k8sNamespaces": ",".join(namespace_names) if namespace_names else None,
            "k8sGitOpsConfig": build_gitops_config(cluster),
        }
        result = post(api_url, token, "/clusters", body)
        ids[cluster["name"]] = result["id"]
        print(f"  cluster: {cluster['name']} ({result['id']})")
    return ids

def seed_stages(api_url: str, token: str, stages: list[dict], cluster_ids: dict[str, str]) -> dict[str, str]:
    print("Seeding stages...")
    ids = {}
    for stage in stages:
        cluster_names = stage.get("clusters", [])
        stage_cluster_ids = []
        for cluster_name in cluster_names:
            cluster_id = cluster_ids.get(cluster_name)
            if cluster_id is None:
                print(f"Error: stage '{stage['name']}' references unknown cluster '{cluster_name}'", file=sys.stderr)
                sys.exit(1)
            stage_cluster_ids.append(cluster_id)
        body = {
            "pipeline": stage["pipeline"],
            "name": stage["name"],
            "description": stage.get("description"),
            "order": stage["order"],
            "deploymentPolicy": stage.get("deployment_policy", "IMMEDIATE"),
            "kubernetesContext": stage.get("kubernetes_context"),
            "namespacePrefix": stage.get("namespace_prefix"),
            "clusterIds": stage_cluster_ids,
        }
        result = post(api_url, token, "/stages", body)
        ids[stage["name"]] = result["id"]
        print(f"  stage: {stage['name']} ({result['id']})")
    return ids


def seed_products(api_url: str, token: str, products: list[dict], stage_ids: dict[str, str]) -> dict[str, str]:
    print("Seeding products...")
    ids = {}
    for product in products:
        crons = []
        for entry in product.get("stage_deployment_crons", []):
            stage_id = stage_ids.get(entry["stage"])
            if stage_id is None:
                print(f"Error: product '{product['name']}' references unknown stage '{entry['stage']}'", file=sys.stderr)
                sys.exit(1)
            crons.append({"stageId": stage_id, "deploymentCron": entry["cron"]})
        product_group_id = None
        if product.get("product_group"):
            product_group_id = ids.get(product["product_group"])
            if product_group_id is None:
                print(
                    f"Error: product '{product['name']}' references unknown/not-yet-seeded product group "
                    f"'{product['product_group']}' — groups must be listed before their members",
                    file=sys.stderr,
                )
                sys.exit(1)
        body = {
            "name": product["name"],
            "description": product.get("description"),
            "isGroup": product.get("is_group", False),
            "productGroupId": product_group_id,
            "stageDeploymentCrons": crons,
        }
        result = post(api_url, token, "/products", body)
        ids[product["name"]] = result["id"]
        print(f"  product: {product['name']} ({result['id']})")
    return ids


def seed_workloads(api_url: str, token: str, workloads: list[dict], product_ids: dict[str, str]) -> dict[str, str]:
    print("Seeding workloads...")
    ids = {}
    for workload in workloads:
        product_id = product_ids.get(workload["product"])
        if product_id is None:
            print(f"Error: workload '{workload['name']}' references unknown product '{workload['product']}'", file=sys.stderr)
            sys.exit(1)
        body = {
            "name": workload["name"],
            "description": workload.get("description"),
            "productId": product_id,
            "kubernetes": workload.get("kubernetes", False),
            "kubernetesKind": workload.get("kubernetes_kind"),
            "kubernetesNameSpace": workload.get("kubernetes_namespace"),
            "pipeline": workload.get("pipeline"),
        }
        result = post(api_url, token, "/workloads", body)
        ids[workload["name"]] = result["id"]
        print(f"  workload: {workload['name']} ({result['id']})")
    return ids


def seed_releases(api_url: str, token: str, releases: list[dict], workload_ids: dict[str, str]) -> None:
    print("Seeding releases...")
    for release in releases:
        workload_id = workload_ids.get(release["workload"])
        if workload_id is None:
            print(f"Error: release '{release['image']}' references unknown workload '{release['workload']}'", file=sys.stderr)
            sys.exit(1)
        body = {"image": release["image"], "description": release.get("description"), "workloadId": workload_id}
        post(api_url, token, "/releases", body)
        print(f"  release: {release['image']}")


def kubectl(*args: str, input_text: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["kubectl", *args], input=input_text, text=True, capture_output=True)


def kubectl_apply(manifest: str, description: str) -> None:
    result = kubectl("apply", "-f", "-", input_text=manifest)
    if result.returncode != 0:
        print(f"Error applying {description}: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    print(f"  {description}: {result.stdout.strip()}")


def namespace_manifest(namespace: str) -> str:
    return f"apiVersion: v1\nkind: Namespace\nmetadata:\n  name: {namespace}\n"


def deployment_manifest(name: str, namespace: str) -> str:
    result = kubectl("create", "deployment", name, f"--image={BOOTSTRAP_IMAGE}", "-n", namespace, "--dry-run=client", "-o", "yaml")
    if result.returncode != 0:
        print(f"Error generating deployment manifest for '{name}': {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout


def statefulset_manifest(name: str, namespace: str) -> str:
    # kubectl has no `create statefulset` generator, unlike deployment — a minimal
    # manifest is the only idempotent way to bootstrap one via `kubectl apply`.
    return f"""apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {name}
  namespace: {namespace}
spec:
  serviceName: {name}
  replicas: 1
  selector:
    matchLabels:
      app: {name}
  template:
    metadata:
      labels:
        app: {name}
    spec:
      containers:
        - name: {name}
          image: {BOOTSTRAP_IMAGE}
"""


def cluster_namespaces(clusters: list[dict]) -> list[str]:
    """Every distinct namespace configured across all clusters' k8s_namespaces."""
    seen: dict[str, None] = {}
    for cluster in clusters:
        for namespace in cluster_namespace_names(cluster):
            seen.setdefault(namespace)
    return list(seen)


def gitops_namespace_map(clusters: list[dict]) -> dict[str, dict]:
    """namespace name -> its k8s_namespaces entry (plus the owning cluster's gitops
    block, under "_cluster_gitops"), for every namespace with use_git_ops: true across
    all clusters. Assumes namespace names are unique across clusters, true for this seed
    data. cdrm never kubectl-applies into one of these — see push_gitops_manifests()."""
    result: dict[str, dict] = {}
    for cluster in clusters:
        gitops = cluster.get("gitops")
        if not gitops:
            continue
        for entry in cluster.get("k8s_namespaces") or []:
            if entry.get("use_git_ops"):
                result[entry["namespace"]] = {**entry, "_cluster_gitops": gitops}
    return result


def workload_stages(stages: list[dict], workload: dict) -> list[dict]:
    """The stages a workload is actually linked to — every stage of its own pipeline,
    matching WorkloadService.create()/update()'s linking rule on the backend."""
    return [stage for stage in stages if stage["pipeline"] == workload.get("pipeline")]


def workload_manifest(workload: dict, namespace: str) -> str:
    name = workload["name"]
    kind = workload["kubernetes_kind"]
    if kind == "DEPLOYMENT":
        return deployment_manifest(name, namespace)
    if kind == "STATEFUL_SET":
        return statefulset_manifest(name, namespace)
    print(f"Error: unknown kubernetes_kind '{kind}' for workload '{name}'", file=sys.stderr)
    sys.exit(1)


def bootstrap_kubernetes_objects(clusters: list[dict], stages: list[dict], workloads: list[dict]) -> None:
    print("Bootstrapping Kubernetes objects in minikube...")
    gitops_namespaces = gitops_namespace_map(clusters)

    for namespace in cluster_namespaces(clusters):
        if namespace in gitops_namespaces:
            continue  # ArgoCD creates it (syncPolicy.syncOptions: CreateNamespace=true)
        kubectl_apply(namespace_manifest(namespace), f"namespace {namespace}")

    for workload in workloads:
        if not workload.get("kubernetes"):
            continue
        base_namespace = workload["kubernetes_namespace"]
        # A workload is only ever linked to the stages of its own pipeline (see
        # WorkloadService.create()/update(), which links every stage sharing the
        # workload's `pipeline` value) — cdrm never deploys it anywhere else.
        for stage in workload_stages(stages, workload):
            namespace = f"{stage.get('namespace_prefix') or ''}{base_namespace}"
            if namespace in gitops_namespaces:
                continue  # pushed to the GitOps repo instead — see push_gitops_manifests()
            manifest = workload_manifest(workload, namespace)
            kind = workload["kubernetes_kind"]
            kubectl_apply(manifest, f"{kind.lower()} {workload['name']} in {namespace}")

    push_gitops_manifests(gitops_namespaces, stages, workloads)


def git_run(*args: str, cwd: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)


def push_gitops_manifests(gitops_namespaces: dict[str, dict], stages: list[dict], workloads: list[dict]) -> None:
    """Pushes the bootstrap Deployment/StatefulSet manifest for every Kubernetes
    workload landing in a GitOps-managed namespace to the demo repo (see
    development/argocd/setup-gitops-repo.sh), instead of kubectl-applying it directly —
    ArgoCD then creates the actual objects by syncing from there (see setup-argocd.sh)."""
    if not gitops_namespaces:
        return

    # One clone, but files land on whichever branch their own namespace resolves to
    # (K8sNamespaceGitopsConfig.gitBranch override, else the cluster-wide default).
    files_by_branch: dict[str, dict[str, str]] = {}
    for workload in workloads:
        if not workload.get("kubernetes"):
            continue
        base_namespace = workload["kubernetes_namespace"]
        for stage in workload_stages(stages, workload):
            namespace = f"{stage.get('namespace_prefix') or ''}{base_namespace}"
            entry = gitops_namespaces.get(namespace)
            if entry is None:
                continue
            branch = entry.get("git_branch") or entry["_cluster_gitops"].get("git_branch", "main")
            file_path = entry["file_expression"].replace("{namespace}", namespace).replace("{workload}", workload["name"])
            manifest = workload_manifest(workload, namespace)
            files_by_branch.setdefault(branch, {})[file_path] = manifest

    if not files_by_branch:
        return

    print("Pushing GitOps-managed manifests to the demo repo...")
    with tempfile.TemporaryDirectory() as tmp:
        clone = subprocess.run(["git", "clone", GITEA_REPO_URL, tmp], capture_output=True, text=True)
        if clone.returncode != 0:
            print(f"Error cloning GitOps repo: {clone.stderr}", file=sys.stderr)
            print("Has development/argocd/setup-gitops-repo.sh been run?", file=sys.stderr)
            sys.exit(1)

        for branch, files in files_by_branch.items():
            checkout = git_run("checkout", branch, cwd=tmp)
            if checkout.returncode != 0:
                print(f"Error checking out branch '{branch}': {checkout.stderr}", file=sys.stderr)
                sys.exit(1)
            for file_path, content in files.items():
                full_path = Path(tmp) / file_path
                full_path.parent.mkdir(parents=True, exist_ok=True)
                full_path.write_text(content)
                print(f"  {file_path} ({branch})")
            git_run("add", "-A", cwd=tmp)
            commit = git_run("commit", "-m", "cdrm seed: bootstrap workload manifests", cwd=tmp)
            if commit.returncode != 0 and "nothing to commit" not in commit.stdout:
                print(f"Error committing to branch '{branch}': {commit.stderr}", file=sys.stderr)
                sys.exit(1)
            push = git_run("push", "origin", branch, cwd=tmp)
            if push.returncode != 0:
                print(f"Error pushing branch '{branch}': {push.stderr}", file=sys.stderr)
                sys.exit(1)


def reset_database() -> None:
    print("Resetting database...")
    if shutil.which("docker") is None:
        print("Error: 'docker' is required but not installed.", file=sys.stderr)
        sys.exit(1)
    statement = f"TRUNCATE TABLE {', '.join(DB_TABLES)};"
    result = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-c", statement],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"Error truncating database tables: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    print(f"  truncated: {', '.join(DB_TABLES)}")


def reset_kubernetes_objects(clusters: list[dict], stages: list[dict], workloads: list[dict]) -> None:
    print("Deleting bootstrapped Kubernetes objects...")
    gitops_namespaces = gitops_namespace_map(clusters)

    if gitops_namespaces:
        # Best-effort: an Application's syncPolicy.automated.selfHeal would otherwise
        # fight the namespace deletion below by recreating what it removes. Silently
        # skipped if ArgoCD (or its Application CRD) was never installed.
        print("Deleting ArgoCD Applications for GitOps-managed namespaces (best-effort)...")
        for namespace in gitops_namespaces:
            subprocess.run(
                ["kubectl", "delete", "application", namespace, "-n", "argocd", "--ignore-not-found"],
                capture_output=True, text=True,
            )

    kinds = {"DEPLOYMENT": "deployment", "STATEFUL_SET": "statefulset"}
    for workload in workloads:
        if not workload.get("kubernetes"):
            continue
        name = workload["name"]
        resource = kinds[workload["kubernetes_kind"]]
        base_namespace = workload["kubernetes_namespace"]
        for stage in workload_stages(stages, workload):
            namespace = f"{stage.get('namespace_prefix') or ''}{base_namespace}"
            if namespace in gitops_namespaces:
                continue  # deleting the namespace below removes these too
            result = kubectl("delete", resource, name, "-n", namespace, "--ignore-not-found")
            if result.returncode != 0:
                print(f"Error deleting {resource} '{name}' in '{namespace}': {result.stderr}", file=sys.stderr)
                sys.exit(1)
            print(f"  {result.stdout.strip() or f'{resource} {name} in {namespace}: not found'}")

    for namespace in cluster_namespaces(clusters):
        result = kubectl("delete", "namespace", namespace, "--ignore-not-found")
        if result.returncode != 0:
            print(f"Error deleting namespace '{namespace}': {result.stderr}", file=sys.stderr)
            sys.exit(1)
        print(f"  {result.stdout.strip() or f'namespace {namespace}: not found'}")


def main() -> None:
    args = parse_args()

    if not args.data.is_file():
        print(f"Error: data file not found: {args.data}", file=sys.stderr)
        sys.exit(1)

    if shutil.which("kubectl") is None:
        print("Error: 'kubectl' is required but not installed.", file=sys.stderr)
        sys.exit(1)

    data = yaml.safe_load(args.data.read_text())

    if args.reset:
        reset_database()
        reset_kubernetes_objects(data["clusters"], data["stages"], data["workloads"])
        print("Done.")
        return

    cluster_ids = seed_clusters(args.api_url, args.token, data["clusters"])
    stage_ids = seed_stages(args.api_url, args.token, data["stages"], cluster_ids)
    product_ids = seed_products(args.api_url, args.token, data["products"], stage_ids)
    workload_ids = seed_workloads(args.api_url, args.token, data["workloads"], product_ids)
    bootstrap_kubernetes_objects(data["clusters"], data["stages"], data["workloads"])
    seed_releases(args.api_url, args.token, data["releases"], workload_ids)
    
    print("Done.")


if __name__ == "__main__":
    main()
