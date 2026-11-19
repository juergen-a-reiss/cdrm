# Changelog

All notable changes to cdrm are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and cdrm uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is 0.x, the REST API and data model
may still change between minor versions.

## [Unreleased]

## [0.1.1] - 2026-11-16

### Added

- **Quickstart**: `quickstart/docker-compose.yaml` runs cdrm with the published images, Postgres and a pre-configured
  Keycloak with demo users — see `quickstart/README.md`.
- The frontend image reads its login settings at container start (`OIDC_AUTHORITY`, `OIDC_CLIENT_ID`) instead of
  having them compiled in, so one image works with any Keycloak.

### Changed

- The backend logs JSON lines (Elastic Common Schema) by default, for log collectors.
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=` (empty) restores plain text; local development and tests keep plain text.

### Fixed

- The 0.1.0 frontend image could not log in: it was built without an OIDC issuer and client ID.
- The frontend image's proxy sent every `/api` request to the backend's root path, and dropped the path and query
  of `/ws` WebSocket requests.

## [0.1.0] - 2026-11-16

First public release, under the Apache License 2.0.

### Added

- **Release management across stages**: promote, roll back and redeploy releases of a workload through the stages of
  one or more pipelines, with scheduled deployment times (cron) per product and stage.
- **Kubernetes deployments**: direct deploys via a kubeconfig context, with post-deploy rollout monitoring and a
  user-friendly result when a cluster is unreachable. Live workload status (image, ready replicas) in the UI.
- **GitOps deployments**: per-namespace option to commit the new image to a Git repository (e.g. for ArgoCD) instead
  of patching the cluster — either a simple file/YAML-path expression or a sandboxed JavaScript template for complex
  repository layouts, with a test dialog to try it out. Commits are authored by the acting user when the User ID
  Storage settings allow storing names and emails, otherwise by cdrm.
- **Products and workloads**: product hierarchy (product groups), product and workload detail views, and an
  "out of pipeline" status for releases deployed outside the regular flow.
- **Release History Dashboard**: graph and server-side sorted/filtered/paged history table; the release history is
  de-normalized so reporting tools can use it directly.
- **Audit log** of every change, with configurable user-ID storage (user UUID or none) and display format.
- **Access control**: OIDC login (e.g. Keycloak), role-based access (RBAC) plus relationship-based restrictions
  (ReBAC) for products, workloads and release actions per stage.
- **Change notifications**: CloudEvents on Kafka for every entity change, and live UI updates via WebSocket.
- **UI**: dark/light mode, branding (logo, colors), role-dependent navigation, basic mobile view, and per-user date,
  time and timezone format.
- **Operations**: Prometheus metrics, OpenAPI/Swagger UI, encrypted Postgres connections, and
  Docker images for backend and frontend published to `ghcr.io`.

[Unreleased]: https://github.com/juergen-a-reiss/cdrm/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/juergen-a-reiss/cdrm/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/juergen-a-reiss/cdrm/releases/tag/v0.1.0
