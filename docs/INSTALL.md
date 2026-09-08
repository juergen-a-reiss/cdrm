# Production Setup

## How to run the cdrm: Docker

This section is mostly for the DevOps team:

The backend and frontend ship as two separate images.

- `Dockerfile` (repo root) — multi-stage build. Compiles the backend with `eclipse-temurin:26-jdk`
  (dependency resolution and compilation are separate cached layers, so an ordinary source change doesn't re-download
  anything), then extracts the boot jar into Spring Boot's layered-jar structure and copies each layer separately into
  an `eclipse-temurin:26-jre` runtime image — the ~150 third-party dependency jars (~90 MB) end up in one layer that
  stays byte-identical across code-only rebuilds, separate from our own compiled classes (under 1 MB). Needs
  `OIDC_ISSUER_URI` and the Postgres datasource settings at runtime (see `application.yaml`); listens on `8080`.
- `frontend/Dockerfile` — builds the Vue app with `node`, then serves the static `dist/` output with
  `nginx:alpine` (`frontend/nginx.conf`). npm dependencies live in their own cached layer during the build stage
  (installed before the source is copied in), but the runtime image doesn't ship any of them at all — only the built
  `dist/` assets, since the browser just needs the bundled JS. Proxies
  `/api/` to a `backend` host on port `8080` — resolved lazily per-request via nginx's `resolver`, so the container
  stays up even if that host isn't reachable yet. Listens on `80`.

Build locally from the repo root:

```bash
docker build -t cdrm-backend .
docker build -t cdrm-frontend -f frontend/Dockerfile frontend
```

`.github/workflows/docker.yml` builds both images on every push and pull request against `master`, and additionally
pushes them to `ghcr.io/<owner>/cdrm-backend` and `ghcr.io/<owner>/cdrm-frontend` on pushes to `master` and on `vX.Y.Z`
tags (pull requests only build, to validate the Dockerfiles without needing registry credentials).

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
| `OIDC_CLIENT_ID`      | no       | `cdrm`                | OIDC client ID cdrm validates tokens against                                |
| `KUBECONFIG`          | no       | `~/.kube/config`      | Kubeconfig file for direct Kubernetes deploys (see Kubernetes Clusters)     |
| `GITOPS_GIT_USERNAME` | no       | *(unset — anonymous)* | Git username to push GitOps commits with (see Kubernetes Clusters)         |
| `GITOPS_GIT_PASSWORD` | no       | *(unset — anonymous)* | Git password/token for `GITOPS_GIT_USERNAME`                               |
| `GITOPS_WORKDIR`      | no       | OS temp directory     | Local working directory for GitOps repo clones                             |

`KUBECONFIG` and the `GITOPS_*` credentials are read straight from the environment and never persisted to Postgres —
same principle for both: the deployment target's credentials are the runtime environment's problem, not the
database's. Whoever manages that environment (a mounted Kubernetes Secret, a file on the host, ...) owns provisioning
and rotating them; cdrm itself never stores them anywhere.

`GITOPS_GIT_USERNAME`/`GITOPS_GIT_PASSWORD` are only needed if any GitOps-managed namespace's repository requires
authenticated pushes (a repo that accepts anonymous pushes, or a deployment with no GitOps-managed namespaces at all,
needs neither). They're sent as an HTTP Basic `Authorization` header per git operation, so this currently only
supports git remotes over `http://`/`https://` — not SSH.

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

