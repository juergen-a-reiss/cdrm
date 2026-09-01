# Continuous Delivery Release Management

Continuous delivery is a widely adopted framework that encourages small, incremental updates to a software product at a
high pace. See
<https://en.wikipedia.org/wiki/Continuous_delivery>. For me, the eye-opener was
<https://www.amazon.de/Continuous-Delivery-Deployment-Automation-Addison-Wesley/dp/0321601912>

Continuous delivery requires that

* Any software artifact is build once and only once. It is then deployed to the first stage of the delivery pipeline.
* This artifact is promoted as-is to the next stage (or discarded). Repeat until discarded or in production.
* Promotion can happen only to the next stage.
* It is easy to roll back to a release that was deployed earlier.

In SaaS organizations, there are usually additional requirements:

* The promotion is usually triggered by different roles in each stage. E.g. a developer might promote to testing, a
  tester to user acceptance and a product owner to production.
* If there are more than one product then promotion might be restricted to a subset of the products.
* Deployment to production should be at a certain time and not immediate. But some stages might require immediate
  deployment after promotion.
* Management needs metrics and stats and stats and metrics.

This tool will make your life easier by:

* Full automation of deployment and rollback.
* Full visibility what artifact was deployed to which stage when, why and by whom.
* Get an overview which product was deployed how often to production. Or rolled back.
* Deployment metrics and stats ;).

## What it does

### Stages

Any continuous delivery pipeline consists of stages. There might be more than one pipelines in an organization. Stages
usually have names like "development",
"staging", "uat" and "production".

Essential in this context is that any pipeline has a first stage and a last stage. To be more precise: A stage might
have a successor - if it does not have a successor, it is the last stage (usually production). Further, any stage in an
organization should have a unique name. Some stages in a pipeline will be deployed immediately, others (usually
production) will be deployed at a certain schedule.

Next, a stage is usually associated with a runtime environment. For example a kubernetes cluster. Or a set of virtual
machines. Or a Proxmox cluster. This tool will concentrate on the handling of kubernetes clusters.

Stages are managed by users with the `cdrm-devops` role.

### Clusters

Clusters are managed by users with the `cdrm-devops` role.

#### Kubernetes Clusters

A kubernetes cluster configuration allows automated deployment of the build artifact to the various stages. There might
be one or more clusters configured. The supported patterns include:

* There is one k8s cluster per stage. This is the cleanest approach. But also the one that needs most resources.
* There is one cluster only. For all stages and all pipelines.
* Or anywhere in between.
* The same workload for different stages will be in different namespaces.

In k8s, applications are usually separated by namespaces. Namespaces are managed usually by the DevOps team. The
namespaces that can be used by the application have to be configured in the clusters. In case a cluster is used for two
or more stages and the usage of namespaces should be restricted to stages, the namespaces have to be whitelisted for
usage in a stage.

In case a cluster is used by more than one stages, it would be a good practice to have "namespace prefix". E.g. the
development stage could define a "dev"
prefix to the namespace name. This will help you to get organized and keep an overview. If the prefix is configured,
then on this stage deployment would be restricted to namespaces with this prefix.

#### Proxmox Clusters

TODO: coming soon.

### Products

Products are managed by users with the `cdrm-productowner` role.

It is all about products. They could be sold many times with little effort. Further products are the driver of a SaaS
organization. And very often also the driver for structuring development and runtime. So, product is a core concept. The
product is the unit that is connected to the stages. It might only be connected to the stages of one pipeline. It could
be connected in a way that deployment starts with the first stage. Or any later stage.

### Workload

Workloads are managed by users with the `cdrm-devops` or `cdrm-developer` role.

The workload describes an artifact that is to be deployed. It is part of a product. A workload is tied to a kubernetes
namespace. The namespace configuration must not contain the prefixes defined in the stages. Instead, on deployment, the
prefix is used (if any) to resolve the namespace.

### Release

Releases are managed by users with the `cdrm-productowner` role.

A release keeps track of the lifecycle of an artifact: The release is created when the artifact is first deployed to the
first stage (the first depends on the product configuration). A release can be promoted to any higher stage. This will -
depending on the config of the stage - result in either immediate deployment or scheduled deployment.

Any release action will be audited in the release history. The release history will be used for stats and metrics.

Releases will be typically created via the build pipeline (GitHub actions, jenkins or anything else). However, the
promotion process is typically done via UI (either the product UI or any other UI that you build that connects to the
API).

#### Release Promotion

After a release is created (for example by a build tool), it is usually in the lowest stage. When it has proved itself
to be useful and it has passed all tests in this stage, it can be promoted. Promotion does the following:

* Identify the next stage.
* Deploy the binary artifact to the next stage.
* Mark the Release object to be in this next stage.
* Increment the counter `cdrm.releases.promoted` with the labels product, workload and stage. This should be used in a
  graphana dashboard.
* Create an immutable entry in the release history used for statistics.

This release is now the *head* release for this stage/workload combination. In general a head release is the release
with the most recent promote or rollback release history entry.

In case the release is for k8s and k8s is not available at this time, the prometheus metric `cdrm.deploy.failed{workload,
stage}` will be incremented. The deploy will be retried with the scheduled deploy job (see below).

#### Release Rollback

A rollback can be done from any non-head release - but not from a head release, obviously. It can be triggered via API
or via GUI. Rollback does the following:

* Target stage is the stage of the release.
* Deploy the binary artifact to the target stage.
* Increment the counter `cdrm.releases.rollback` with the labels product, workload and stage. This should be used in a
  graphana dashboard.
* Create an immutable entry in the release history used for statistics.

This release is now the *head* release for this stage/workload combination.

It is not possible to rollback to a release that already was promoted to the next stage.

#### Release Redeploy

A redeploy can be done from any release. It can be done to the current and any lower stage. It can only be done to the
current stage if the release is the head release. Redeploy cannot be done to a higher stage. It can be triggered via API
or via GUI. The idea of redeploy is to have a way to safely redeploy the current image on the target cluster if this
would be necessary for any reason. Or deploy anything that is in production to a lower stage - for example to examine
any issue that happens in production. Redeploy does the following:

* Deploy the binary artifact to the selected stage.
* Increment the counter `cdrm.releases.redeploy` with the labels product, workload and stage. This should be used in a
  graphana dashboard.
* Create an immutable entry in the release history used for statistics.

This redeploy does not change the *head* status.

#### Deployment Scheduling

Each stage has a deployment policy: **immediate** or **scheduled**. An immediate-policy stage attempts to deploy the
artifact right away as part of the promote/rollback/redeploy request. A scheduled-policy stage defers the actual
deployment to a cron schedule configured per product and stage (e.g. only deploy to production at 02:00 on weekdays) -
the release history entry is created immediately, but the artifact is not deployed until the configured time is reached.

A background job polls every minute for release history entries that have not yet been deployed, and deploys whichever
of them are due - either because they belong to an immediate-policy stage and the synchronous deploy attempt at
promotion time failed (it is retried here), or because a scheduled-policy stage's cron time has passed. On every tick,
the job re-derives everything it needs from the database: which entries are still pending, and when each one is due,
computed fresh from the entry's creation time and the configured cron. Nothing is cached in memory between ticks, so
scheduled deployments survive an application restart - the job simply resumes from the database state on the first tick
after startup. If the application was down when a scheduled deployment's cron time should have fired, it is deployed
late, on the next tick after the app comes back up, rather than being skipped.

## Release History Dashboard

### Release History Graph

The ultimate management questions will be answered here:

* How many releases/rollbacks did we do last month to production?
* How is the releases/rollbacks per month trend?
* How does this look like by product or by workload?

### Release History Details

Here we see the release history details als a table view.

## Access Control

Access control is secured via OpenId Connect.

### RBAC Role Based Access Control

`cdrm-devops`: Can edit the views that are needed to setup the system and adapt it to the infrastructure. Namely:

- clusters
- stages
- workloads

`cdrm-productowner`: Can manage the data inside releases view. Can manage products. Can view the rest of the data.

`cdrm-developer`: Can create releases and deploy them to the first stage. Can create and edit workloads. Can view the
rest of the data.

`cdrm-manager`: Can view the data.

### ReBAC Relationship Based Access Control

The access to resources can be restricted by additional user attributes in the JWT token. The following attributes are
supported:

- `cdrm-products`: a list of product names. If set, the user will be allowed to see the configured products.
  Restrictions in products will also apply in workloads and releases. There must be an exact match with the product
  name.
- `cdrm-workloads`: a list of workload names. If set, the user will be allowed to see the configured workloads.
  Restrictions in workloads will also apply in releases. There must be an exact match with the workload name.
- `cdrm-release-actions`: a list of release actions that are allowed for the user. The general format is the action name
  followed by a comma separated list of stages. For example: `promote: dev, qa` will allow the user to create release
  objects and promote to qa. Valid actions are: promote, rollback, redeploy, delete, edit.

If an attribute is not set, then ReBAC does not apply for this user for this attribute.

## Docker

The backend and frontend ship as two separate images.

- `Dockerfile` (repo root) — multi-stage build. Compiles the backend with `eclipse-temurin:26-jdk`
  (dependency resolution and compilation are separate cached layers, so an ordinary source change
  doesn't re-download anything), then extracts the boot jar into Spring Boot's layered-jar structure and
  copies each layer separately into an `eclipse-temurin:26-jre` runtime image — the ~150 third-party
  dependency jars (~90 MB) end up in one layer that stays byte-identical across code-only rebuilds,
  separate from our own compiled classes (under 1 MB). Needs `OIDC_ISSUER_URI` and the Postgres
  datasource settings at runtime (see `application.yaml`); listens on `8080`.
- `frontend/Dockerfile` — builds the Vue app with `node`, then serves the static `dist/` output with
  `nginx:alpine` (`frontend/nginx.conf`). npm dependencies live in their own cached layer during the
  build stage (installed before the source is copied in), but the runtime image doesn't ship any of
  them at all — only the built `dist/` assets, since the browser just needs the bundled JS. Proxies
  `/api/` to a `backend` host on port `8080` — resolved lazily per-request via nginx's `resolver`, so
  the container stays up even if that host isn't reachable yet. Listens on `80`.

Build locally from the repo root:

```bash
docker build -t cdrm-backend .
docker build -t cdrm-frontend -f frontend/Dockerfile frontend
```

`.github/workflows/docker.yml` builds both images on every push and pull request against `master`, and
additionally pushes them to `ghcr.io/<owner>/cdrm-backend` and `ghcr.io/<owner>/cdrm-frontend` on pushes
to `master` and on `vX.Y.Z` tags (pull requests only build, to validate the Dockerfiles without needing
registry credentials).

