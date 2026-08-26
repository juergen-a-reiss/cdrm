#!/usr/bin/env python3
"""Seeds the cdrm backend with sample stages, products, workloads, and releases.

Intended for a freshly migrated, empty database — re-running against
already-seeded data will fail on unique-name constraints.

Also bootstraps the underlying Kubernetes Deployment/StatefulSet objects in
minikube for every kubernetes-managed workload, in each stage's namespace
(stage namespace_prefix + workload kubernetes_namespace) — cdrm only patches
an existing object's image on promotion, it doesn't create one. That step is
idempotent (kubectl apply) and safe to re-run on its own.

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

def seed_clusters(api_url: str, token: str, clusters: list[dict]) -> dict[str, str]:
    print("Seeding clusters...")
    print(clusters)
    ids = {}
    for cluster in clusters:
        body = {
            "name": cluster["name"],
            "description": cluster.get("description"),
            "clusterType": cluster.get("cluster_type"),
            "url": cluster.get("url"),
            "k8sNamespaces": cluster.get("k8s_namespaces"),
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
        body = {"name": product["name"], "description": product.get("description"), "stageDeploymentCrons": crons}
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
            print(f"Error: release '{release['binary_url']}' references unknown workload '{release['workload']}'", file=sys.stderr)
            sys.exit(1)
        body = {"binaryUrl": release["binary_url"], "description": release.get("description"), "workloadId": workload_id}
        post(api_url, token, "/releases", body)
        print(f"  release: {release['binary_url']}")


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
        for namespace in (cluster.get("k8s_namespaces") or "").split(","):
            namespace = namespace.strip()
            if namespace:
                seen.setdefault(namespace)
    return list(seen)


def bootstrap_kubernetes_objects(clusters: list[dict], stages: list[dict], workloads: list[dict]) -> None:
    print("Bootstrapping Kubernetes objects in minikube...")
    for namespace in cluster_namespaces(clusters):
        kubectl_apply(namespace_manifest(namespace), f"namespace {namespace}")

    for workload in workloads:
        if not workload.get("kubernetes"):
            continue
        name = workload["name"]
        kind = workload["kubernetes_kind"]
        base_namespace = workload["kubernetes_namespace"]
        for stage in stages:
            namespace = f"{stage.get('namespace_prefix') or ''}{base_namespace}"
            if kind == "DEPLOYMENT":
                manifest = deployment_manifest(name, namespace)
            elif kind == "STATEFUL_SET":
                manifest = statefulset_manifest(name, namespace)
            else:
                print(f"Error: unknown kubernetes_kind '{kind}' for workload '{name}'", file=sys.stderr)
                sys.exit(1)
            kubectl_apply(manifest, f"{kind.lower()} {name} in {namespace}")


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
    kinds = {"DEPLOYMENT": "deployment", "STATEFUL_SET": "statefulset"}
    for workload in workloads:
        if not workload.get("kubernetes"):
            continue
        name = workload["name"]
        resource = kinds[workload["kubernetes_kind"]]
        base_namespace = workload["kubernetes_namespace"]
        for stage in stages:
            namespace = f"{stage.get('namespace_prefix') or ''}{base_namespace}"
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
    seed_releases(args.api_url, args.token, data["releases"], workload_ids)
    bootstrap_kubernetes_objects(data["clusters"], data["stages"], data["workloads"])

    print("Done.")


if __name__ == "__main__":
    main()
