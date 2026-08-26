# Platform Dev Setup

Local development environment running a subset of very commonly used 3rd party software for a platform project as a single Docker Compose project. This project is inteded for _developers_. Feel free to clone it and change whatever you see fit. After all, the target is that a new developer can start the platform locally with a single command (or maybe two ;)). 

## Prerequisites

- Docker with the Compose plugin

## Usage

### Starting services

```bash
./up.sh
```

### Stopping services

```bash
./down.sh
```

### Available services

| Service    | Default port | Description              |
|------------|-------------|--------------------------|
| `postgres` | 5432        | PostgreSQL 18            |
| `keycloak` | 2305        | Keycloak 26 (HTTP)       |
| ``    |         |    |

To start only a subset, edit `components`:

```
postgres
keycloak
```

## PostgreSQL

Credentials: `dockers / dockers`, default database: `dockers`.

Connect via psql:

```bash
docker exec -it postgres psql -U dockers -d dockers
```

The `keycloak` database is created automatically on first initialization.

> If the volume already exists without the `keycloak` database, create it manually:
> ```bash
> docker exec postgres psql -U dockers -c "CREATE DATABASE keycloak;"
> ```

### Resetting the PostgreSQL volume

```bash
docker compose down
docker volume rm platform-dev-setup_db-data
./up.sh
```

## Keycloak

Admin console: http://localhost:2305 (credentials: `admin / admin`)

Keycloak requires PostgreSQL — include `postgres` in `components` whenever `keycloak` is active.

### Automated configuration

Run `start.sh` after `up.sh` to apply the Ansible-based configuration:

```bash
./up.sh
./start.sh
```

`start.sh` waits for Keycloak to be ready, then runs the playbook inside an `alpine/ansible` container on `shared_net`. The playbook is idempotent — safe to re-run.

**What it configures:**

- Creates the `cdrm` realm ("Continuous Delivery Release Management") with a PKCE-enabled `cdrm` client, the client roles `cdrm-admin`, `cdrm-developer`, `cdrm-productowner`, and one test user per role (password `test`)

Playbooks: `ansible/playbooks/configure-keycloak-cdrm.yml`

### Custom theme

A custom login theme is mounted from `src/keycloak-theme/` into the `platform` theme slot. The background image is at `src/keycloak-theme/login/resources/img/background.png`.

## Networking

All containers share the `shared_net` bridge network (`172.30.200.0/24`), allowing inter-container communication by service name.

## Minikube

cdrm's local dev setup deploys into a `minikube` cluster (the default context name `minikube start` configures). All four cdrm stages (development/qa/staging/production) share that one cluster, so each stage's `namespace_prefix` (see `seed/data.yaml`) keeps them from colliding on namespace.

You might need to instal and run:

```bash
sudo apt install ansible python3-kubernetes
ansible-galaxy collection install -r development/ansible/requirements.yml
./development/configure-minikube.sh
```

### Prerequisites

```bash
sudo apt install ansible python3-kubernetes
ansible-galaxy collection install -r ansible/requirements.yml
```

### Setting up namespaces

```bash
minikube start
./configure-minikube.sh
```

Unlike `start.sh`, this runs directly on the host rather than inside a container — minikube's docker driver puts the API server on its own docker network, not `shared_net`, so a containerized ansible run would need extra network/cert-mount plumbing to reach it. Running on the host reuses the same kubeconfig/context the cdrm backend itself resolves `minikube` against. The playbook is idempotent — safe to re-run.

Playbook: `ansible/playbooks/configure-minikube-namespaces.yml`
