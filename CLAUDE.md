# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build          # compile + test
./gradlew bootRun        # run locally
./gradlew test           # run all tests
./gradlew test --tests "dev.juergenreiss.cdrm.SomeTest"  # run single test class
./gradlew clean          # wipe build artifacts
```

## Architecture

**cdrm** is a Kotlin/Spring Boot 4 backend service. Package root: `dev.juergenreiss.cdrm`.

Key dependencies already wired in `build.gradle.kts`:
- **Spring Web MVC** — REST controllers
- **Spring Data JPA + PostgreSQL** — persistence layer
- **Spring Security** — authentication/authorization
- **Quartz Scheduler** — background jobs
- **SpringDoc OpenAPI** — Swagger UI at `/swagger-ui.html`
- **Actuator + Micrometer/Prometheus** — metrics at `/actuator/prometheus`

Configuration lives in `src/main/resources/application.yaml`. The app requires a PostgreSQL instance at runtime — datasource settings must be provided there or via environment variables.

Kotlin compiler targets JVM 24 (toolchain targets Java 26 but is constrained by Kotlin compatibility).

## Local development environment

Lives in `development/` (own `docker-compose.yaml`, see `development/README.md`). Docker Compose runs Postgres,
Keycloak (http://localhost:2305), Gitea (http://localhost:3000) and Kafka. Workloads are deployed into a local
`minikube` cluster; `development/start.sh` starts it and sets up ArgoCD and the ingress.

Nothing in minikube is reached via port-forwarding — UIs are exposed through the minikube `ingress` addon
(ingress-nginx), set up by `development/ingress/setup-ingress.sh` (idempotent):

- `https://argocd.<domain>` — ArgoCD UI
- `https://dashboard.<domain>` — Kubernetes Dashboard

`<domain>` defaults to `<minikube ip>.nip.io` (e.g. `192.168.49.2.nip.io`). Override it with `DEV_DOMAIN` in the
git-ignored `development/local.env` (template: `local.env.example`), e.g. `juergenareiss.dev` — that needs an
`/etc/hosts` entry (the script prints it). TLS is terminated at the ingress with a wildcard cert signed by a local
dev CA in `development/ingress/certs/` (git-ignored); `dev-ca.crt` must be imported into the browser, which is
mandatory for `.dev` (HSTS-preloaded TLD). The seeded workloads themselves are intentionally not exposed.

`development/seed.py` populates cdrm and the cluster from `development/seed/data.yaml`; `seed.py --reset` truncates
cdrm's tables, deletes all workload namespaces and ArgoCD Applications, and clears the GitOps repos.
