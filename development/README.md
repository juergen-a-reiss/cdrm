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

- Creates the `cdrm` realm ("Continuous Delivery Release Management") with a PKCE-enabled `cdrm` client, the client roles `cdrm-devops`, `cdrm-developer`, `cdrm-productowner`, and one test user per role (password `test`)

Playbooks: `ansible/playbooks/configure-keycloak-cdrm.yml`

## Networking

All containers share the `shared_net` bridge network (`172.30.200.0/24`), allowing inter-container communication by service name.

## Frontend

The Vue frontend lives in `frontend/` at the repo root (not part of this Docker Compose project).

```bash
cd frontend
npm install
npm run dev
```

Serves on http://localhost:5173 — the address already registered as the `cdrm` Keycloak client's redirect URI by `start.sh`. The Vite dev server proxies `/api/*` to `http://localhost:8080`, so the cdrm backend must also be running (`./gradlew bootRun` from the repo root). `frontend/.env` supplies `VITE_OIDC_AUTHORITY`/`VITE_OIDC_CLIENT_ID` pointing at the local Keycloak realm — already present for this dev setup.


## Minikube

cdrm's local dev setup deploys into a `minikube` cluster (the default context name `minikube start` configures). All four cdrm stages (development/qa/staging/production) share that one cluster, so each stage's `namespace_prefix` (see `seed/data.yaml`) keeps them from colliding on namespace. To get a graphical view use `minikube dashboard --port=1964`.


### Setting up seed data

This script is not idempotent.  It also creates the namespaces listed in `seed/data.yaml`'s `clusters[].k8s_namespaces` (and the bootstrapped Deployment/StatefulSet objects) in minikube — keep that list in sync with the stages' `namespace_prefix` and workloads' `kubernetes_namespace` values.

Run ./seed.py --token 

```bash
./seed.py --token 
```

With a token for a devops role. Consider using the keycloak.http to get such a token.


### Generating chart demo history

`./generate-release-history.py` backfills many months of realistic-looking release
history for one workload (default `platform-api`), so the Release History Dashboard's
chart and filters have more than a handful of same-day rows to show. Each release
also funnels down the pipeline — by default a 20%/33%/50% per-release chance of
reaching qa/staging/production respectively — so there are far more dev releases
than production ones, like a real deployment pipeline. `--start-per-week`/
`--end-per-week` describe the ramp you'll see at the *last* stage (default 1 -> 4
releases/week); the script scales up how many releases it starts at the first stage
to compensate for the funnel. It writes directly to the `release_history` table
(talks to Postgres the same way `seed.py --reset` does), so it doesn't need `--token`
and should be run after `seed.py`.

```bash
./generate-release-history.py
```

Safe to re-run: pass `--replace` to delete this script's own previously generated
rows for the workload first. `--dry-run` prints the plan without writing anything.
See the script's `--help` for the release-rate ramp and excluded-months options.

