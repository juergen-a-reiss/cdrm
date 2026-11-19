# cdrm quickstart

Try cdrm locally in a few minutes with the published Docker images. Requires Docker with Compose.

```bash
cd quickstart
docker compose up -d
```

Then open **http://localhost:8081** and log in with one of the demo users (password `cdrm` for all):

| User           | Role                | Can                                                        |
|----------------|---------------------|------------------------------------------------------------|
| `devops`       | `cdrm-devops`       | everything, including Configuration                        |
| `productowner` | `cdrm-productowner` | manage products, promote and roll back releases            |
| `developer`    | `cdrm-developer`    | manage workloads and releases                              |
| `manager`      | `cdrm-manager`      | read-only overview                                         |

Start with `devops`: create a few stages (e.g. `dev`, `qa`, `prod` in one pipeline), a product, a workload and a
release, then promote it through the stages with `productowner`.

## What's running

| Service    | Address                | Notes                                                         |
|------------|------------------------|---------------------------------------------------------------|
| `frontend` | http://localhost:8081  | cdrm UI; proxies `/api` and `/ws` to the backend              |
| `backend`  | (internal only)        | cdrm REST API                                                 |
| `keycloak` | http://localhost:8180  | Login; admin console with `admin` / `admin`                   |
| `postgres` | (internal only)        | cdrm's database, kept in the `postgres-data` volume           |

Kafka change notifications are switched off. Kubernetes and GitOps deployments need a cluster or git repository
cdrm can reach; without one, releases are still tracked through the stages, just not deployed anywhere.

The images default to version `0.1.1`; set `CDRM_VERSION` to use another one, e.g.
`CDRM_VERSION=latest docker compose up -d`.

## Stop

```bash
docker compose down      # keeps the data
docker compose down -v   # also deletes the data
```

## Not for production

Demo passwords, Keycloak in dev mode with its embedded database, and plain HTTP. For a real installation, see
[docs/INSTALL.md](../docs/INSTALL.md).
