# Continuous Delivery Release Management

Continuous delivery is a widely adopted framework that encourages small, incremental updates to a software product at a
high pace. See https://en.wikipedia.org/wiki/Continuous_delivery. For me, the eye-opener
was https://www.amazon.de/Continuous-Delivery-Deployment-Automation-Addison-Wesley/dp/0321601912

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

# What it does

## Stages

Any continuous delivery pipeline consists of stages. There might be more than one pipelines in an organization. Stages
usually have names like "development", "staging", "uat" and "production".

Essential in this context is that any pipeline has a first stage and a last stage. To be more precise: A stage might
have a successor - if it does not have a successor, it is the last stage (usually production). Further, any stage in an
organization should have a unique name. Some stages in a pipeline will be deployed immediately, others (usually
production) will be deployed at a certain schedule.

Next, a stage is usually associated with a runtime environment. For example a kubernetes cluster. Or a set of virtual
machines. Or a Proxmox cluster. This tool will concentrate on the handling of kubernetes clusters.

Stages are managed by users with the "cdrm-devops" role.

## Clusters

Clusters are managed by users with the "cdrm-devops" role.

### Kubernetes Clusters

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
development stage could define a "dev" prefix to the namespace name. This will help you to get organized and keep an
overview. If the prefix is configured, then on this stage deployment would be restricted to namespaces with this prefix.

### Proxmox Clusters

TODO: coming soon.

## Products

Products are managed by users with the "cdrm-productowner" role.

It is all about products. They could be sold many times with little effort. Further products are the driver of a SaaS
organization. And very often also the driver for structuring development and runtime. So, product is a core concept. The
product is the unit that is connected to the stages. It might only be connected to the stages of one pipeline. It could
be connected in a way that deployment starts with the first stage. Or any later stage.

## Workload

Workloads are managed by users with the "cdrm-devops" or "cdrm-developer" role.

The workload describes an artifact that is to be deployed. It is part of a product. A workload is tied to a kubernetes
namespace. The namespace configuration must not contain the prefixes defined in the stages. Instead, on deployment, the
prefix is used (if any) to resolve the namespace.

## Release

A release keeps track of the lifecycle of an artifact: The release is created when the artifact is first deployed to the
first stage (the first depends on the product configuration). A release can be promoted to any higher stage. This will -
depending on the config of the stage - result in either immediate deployment or scheduled deployment.

Any release action will be audited in the release history. The release history will be used for stats and metrics.

Releases will be typically created via the build pipeline (GitHub actions, jenkins or anything else). However, the
promotion process is typically done via UI (either the product UI or any other UI that you build that connects to the
API).

