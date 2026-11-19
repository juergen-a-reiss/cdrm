# Production Setup

## How to run the cdrm: Docker

This section is mostly for the DevOps team:

The backend and frontend ship as two separate images.

- `Dockerfile` (repo root) — multi-stage build. Compiles the backend with `eclipse-temurin:26-jdk`
  (dependency resolution and compilation are separate cached layers, so an ordinary source change doesn't re-download
  anything), then extracts the boot jar into Spring Boot's layered-jar structure and copies each layer separately into
  an `eclipse-temurin:26-jre` runtime image — the ~145 third-party dependency jars (~175 MB, about 65 MB of it the
  GraalVM JavaScript engine for GitOps templates) end up in one layer that stays byte-identical across code-only
  rebuilds, separate from our own compiled classes (about 2 MB). Needs
  `OIDC_ISSUER_URI` and the Postgres datasource settings at runtime (see `application.yaml`); listens on `8080`. The
  runtime image includes `git` and `openssh-client`, which GitOps deployments use to commit and push.
- `frontend/Dockerfile` — builds the Vue app with `node`, then serves the static `dist/` output with
  `nginx:alpine` (`frontend/nginx.conf`). npm dependencies live in their own cached layer during the build stage
  (installed before the source is copied in), but the runtime image doesn't ship any of them at all — only the built
  `dist/` assets, since the browser just needs the bundled JS. Proxies
  `/api/` and `/ws` to a `backend` host on port `8080` — resolved lazily per-request via nginx's `resolver`, so the
  container stays up even if that host isn't reachable yet. Listens on `80`. Configured at container start (nothing
  installation-specific is compiled into the image):

  | Variable          | Required | Default | Purpose                                                                     |
  |-------------------|----------|---------|-----------------------------------------------------------------------------|
  | `OIDC_AUTHORITY`  | yes      | —       | OIDC issuer URL as the *browser* reaches it, e.g. `https://sso.example.com/realms/cdrm` |
  | `OIDC_CLIENT_ID`  | no       | `cdrm`  | Public OIDC client of the frontend; must match the backend's `OIDC_CLIENT_ID` |

  `quickstart/docker-compose.yaml` is a complete, runnable example of both images together.

Build locally from the repo root:

```bash
docker build -t cdrm-backend .
docker build -t cdrm-frontend -f frontend/Dockerfile frontend
```

### Published images

Pre-built images for `linux/amd64` and `linux/arm64` are published to the GitHub Container Registry:

```bash
docker pull ghcr.io/juergen-a-reiss/cdrm-backend:0.1.1
docker pull ghcr.io/juergen-a-reiss/cdrm-frontend:0.1.1
```

| Tag             | Meaning                                                                 |
|-----------------|-------------------------------------------------------------------------|
| `X.Y.Z`         | A release (see `CHANGELOG.md`) — use this in production                 |
| `X.Y`           | Newest patch release of that minor version                              |
| `latest`        | Newest release                                                          |
| `master`        | Newest unreleased build of the `master` branch                          |
| `sha-<commit>`  | One specific commit                                                     |

`.github/workflows/ci.yml` runs the backend tests and the frontend type-check/build on every push and pull request
against `master`, then builds both images. Pull requests only build them (amd64 only, no registry credentials needed);
pushes to `master` and `vX.Y.Z` tags also publish them.

## Releasing

cdrm has exactly one version number: `version` in `gradle.properties`. The frontend has none of its own.

1. Set `version` in `gradle.properties` to the new version, and in `CHANGELOG.md` move the `[Unreleased]` entries into
   a new `## [X.Y.Z] - <date>` section (and update the compare links at the bottom). Also update the default
   `CDRM_VERSION` in `quickstart/docker-compose.yaml` (and its mention in `quickstart/README.md`) and the example
   `docker pull` commands above.
2. Commit, merge to `master`, then tag and push:
   ```bash
   git tag -a vX.Y.Z -m "cdrm X.Y.Z"
   git push origin vX.Y.Z
   ```
3. The CI workflow checks that the tag matches `gradle.properties`, runs the tests, publishes the images, and creates
   the GitHub Release with that version's `CHANGELOG.md` section as release notes.

After the first release, Liquibase changesets that have shipped must never be edited again — every schema change needs a
new changeset, or existing installations can't upgrade.

## Production Setup

Nothing environment-specific is hardcoded — `application.yaml` (always active) reads everything below from the
environment, with sensible defaults where one makes sense. `application-dev.yaml` (active only under the `dev` Spring
profile used by `./gradlew bootRun` locally) is what supplies convenience values for local development; none of it
applies in production, where only `application.yaml`'s defaults (or lack thereof) are in effect.

| Variable              | Required | Default              | Purpose                                                                    |
|-----------------------|----------|-----------------------|-----------------------------------------------------------------------------|
| `OIDC_ISSUER_URI`     | yes      | —                     | OpenID Connect issuer URL (see Access Control)                              |
| `DB_URL`              | yes      | —                     | Postgres JDBC URL                                                           |
| `DB_USERNAME`         | yes      | —                     | Postgres user                                                               |
| `DB_PASSWORD`         | yes      | —                     | Postgres password                                                          |
| `DB_SSL_MODE`         | no       | `disable`             | Postgres connection encryption: `disable`/`allow`/`prefer`/`require`/`verify-ca`/`verify-full` (pgjdbc's own `sslmode` values) |
| `OIDC_CLIENT_ID`      | no       | `cdrm`                | OIDC client ID cdrm validates tokens against                                |
| `KUBECONFIG`          | no       | `~/.kube/config`      | Kubeconfig file for direct Kubernetes deploys (see Kubernetes Clusters)     |
| `CDRM_GITOPS_REPOSITORIES_<n>_*` | no | *(none)*     | GitOps repositories and their credentials, see below                       |
| `GITOPS_WORKDIR`      | no       | OS temp directory     | Local working directory for GitOps repo clones                             |
| `GITOPS_COMMIT_AUTHOR_NAME`  | no | `cdrm`           | Committer name of cdrm's GitOps commits, and their author unless the acting user is used (see the README's "GitOps commit author") |
| `GITOPS_COMMIT_AUTHOR_EMAIL` | no | `cdrm@localhost` | Committer email of cdrm's GitOps commits (same rule as above)               |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | no | `ecs`      | Log format on stdout: JSON as `ecs`, `logstash` or `gelf`; empty for plain text (see Logging) |

`KUBECONFIG` and the GitOps repository credentials are read straight from the environment and never persisted to Postgres —
same principle for both: the deployment target's credentials are the runtime environment's problem, not the
database's. Whoever manages that environment (a mounted Kubernetes Secret, a file on the host, ...) owns provisioning
and rotating them; cdrm itself never stores them anywhere.

### GitOps repositories

The git repositories cdrm may push GitOps commits to are configured per repository, numbered from `0`. They also make
up the repository dropdown when editing a cluster. A repository that accepts anonymous pushes needs no entry, and
neither does a deployment without GitOps-managed namespaces.

| Variable                                         | Purpose                                                                     |
|--------------------------------------------------|-----------------------------------------------------------------------------|
| `CDRM_GITOPS_REPOSITORIES_0_URL`                 | Repository URL, exactly as configured on clusters/namespaces                |
| `CDRM_GITOPS_REPOSITORIES_0_AUTH`                | `NONE` (default), `BASIC` or `SSH_KEY`                                      |
| `CDRM_GITOPS_REPOSITORIES_0_USERNAME`            | `BASIC`: user name (required)                                               |
| `CDRM_GITOPS_REPOSITORIES_0_PASSWORD`            | `BASIC`: password or access token                                           |
| `CDRM_GITOPS_REPOSITORIES_0_SSH_PRIVATE_KEY_PATH`| `SSH_KEY`: path to a mounted private key file (required)                    |
| `CDRM_GITOPS_REPOSITORIES_0_SSH_KNOWN_HOSTS_PATH`| `SSH_KEY`: path to a `known_hosts` file (optional, default ssh's own)       |

A second repository uses `CDRM_GITOPS_REPOSITORIES_1_*`, and so on. `BASIC` credentials are sent as an HTTP Basic
`Authorization` header per git operation (for `http://`/`https://` remotes); `SSH_KEY` passes the key to `ssh` per
git operation (for `ssh://`/`git@host:` remotes). Neither is ever written to the clone's `.git/config`. A repository
URL without a matching entry is pushed to anonymously.

`DB_SSL_MODE` is a plain pgjdbc connection property (`spring.datasource.hikari.data-source-properties.sslmode`), not
something cdrm interprets itself — both an unencrypted and an encrypted connection are supported by leaving it at its
default or setting it, respectively; no code or schema change either way. `require` encrypts the connection but
doesn't check the server's identity; `verify-ca`/`verify-full` additionally validate the server's certificate against
the JVM's default trust store, which needs no further configuration for a certificate signed by a real (publicly
trusted, or already-installed-in-the-JVM) CA — e.g. most managed Postgres offerings. See `development/README.md`'s
"Encrypted connections (TLS)" section for exercising this locally against a self-signed certificate.

### Logging

The backend logs to stdout as one JSON object per line in the [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html)
(ECS), ready for a log collector such as Grafana Alloy/Loki, Fluent Bit or Filebeat — no parsing rules needed. Each
line carries `@timestamp`, `log.level`, `log.logger`, `message`, the thread, and `service.name`/`service.version`
(`cdrm` and the release version); exceptions add `error.type`, `error.message` and `error.stack_trace`.

```json
{"@timestamp":"2026-11-19T12:00:27.999Z","log":{"level":"INFO","logger":"dev.juergenreiss.cdrm.CdrmApplicationKt"},"service":{"name":"cdrm","version":"0.1.1"},"message":"Started CdrmApplicationKt in 6.1 seconds","ecs":{"version":"8.11"}}
```

`LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` or `gelf` switches to another JSON shape; an empty value
(`LOGGING_STRUCTURED_FORMAT_CONSOLE=`) to Spring's plain-text format, e.g. for reading logs by eye. Local development
(`./gradlew bootRun`, the `dev` profile) and the tests log plain text.

## Frontend Customization

The frontend supports a dark/light mode toggle out of the box (a button in the app bar, remembered per browser via
`localStorage`, defaulting to the OS/browser preference on first visit) — no configuration needed for that part.

Reskinning cdrm for a company — colors, font, and the logo shown in the app bar — is a single CSS file:
`frontend/src/styles/brand.css`. No component or TypeScript changes are needed for any of it; the file is loaded
after Vuetify's own stylesheet, so its values simply win.

| What                          | How                                                                             |
|-------------------------------|----------------------------------------------------------------------------------|
| Colors (light and dark theme) | Override the `--v-theme-*` variables (Vuetify's own RGB-triplet format, each needs `!important` — see `brand.css`'s comments for why) under the `.v-theme--light`/`.v-theme--dark` selectors |
| Font                          | Set `--brand-font-family`. For a custom font file/webfont, add an `@font-face` (or a `<link>` in `frontend/index.html`) yourself and reference its family name here |
| Logo (app bar)                | Set `--brand-logo-url` to any image URL — relative, absolute, or a `data:` URI |
| Favicon (browser tab icon)    | Can't be done via CSS — browsers load `<link rel="icon">` directly, outside the CSS cascade. Replace the file at `frontend/public/favicon.svg` instead (same file `--brand-logo-url` points at by default, so replacing it alone reskins both) |

Rebuild/redeploy the frontend after editing `brand.css` — it's a static asset baked in at `npm run build` time, not
something read at runtime.

### Example

As an illustration (not an actual partnership or endorsement — just colors and a font pulled from
a public stylesheet, to show a real-looking result rather than arbitrary values):

```css
:root {
  --brand-logo-url: url('/favicon.svg');
  --brand-font-family: 'Inter', sans-serif;
}

.v-theme--light {
  --v-theme-primary: 26, 53, 82 !important;       /* #1a3552 */
  --v-theme-on-primary: 255, 255, 255 !important;
  --v-theme-secondary: 77, 98, 121 !important;    /* #4d6279 */
  --v-theme-on-secondary: 255, 255, 255 !important;
}

.v-theme--dark {
  --v-theme-primary: 179, 188, 197 !important;    /* #b3bcc5 */
  --v-theme-on-primary: 0, 31, 63 !important;
  --v-theme-secondary: 128, 143, 159 !important;  /* #808f9f */
  --v-theme-on-secondary: 0, 0, 0 !important;
}
```

Also add `frontend/public/favicon.svg` (and, since `--brand-logo-url` defaults to that same path, the app-bar logo
updates with it) and, if using a webfont like Inter rather than a system font, a `<link>` to it in
`frontend/index.html`.

