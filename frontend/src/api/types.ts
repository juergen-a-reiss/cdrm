// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

export type DeploymentPolicy = 'IMMEDIATE' | 'SCHEDULED'

export type ClusterType = 'K8S' | 'PROXMOX'

export interface ClusterRequest {
  name: string
  description: string | null
  clusterType: ClusterType
  url: string
  k8sNamespaces?: string | null
}

export interface ClusterResponse {
  id: string
  name: string
  description: string | null
  clusterType: ClusterType
  url: string
  k8sNamespaces: string | null
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export interface StageClusterInfo {
  id: string
  name: string
  clusterType: ClusterType
}

export interface StageRequest {
  name: string
  description: string | null
  order: number
  deploymentPolicy: DeploymentPolicy
  // Kubeconfig context name to deploy into — required only for stages that will
  // receive kubernetes-managed workloads.
  kubernetesContext?: string | null
  // Prepended to a workload's kubernetesNameSpace when deploying into this stage —
  // only needed when multiple stages share the same cluster (kubernetesContext).
  namespacePrefix?: string | null
  // Omit/null = leave existing cluster links unchanged; [] = unlink from all clusters.
  clusterIds?: string[] | null
}

export interface StageResponse {
  id: string
  name: string
  description: string | null
  order: number
  deploymentPolicy: DeploymentPolicy
  kubernetesContext: string | null
  namespacePrefix: string | null
  clusters: StageClusterInfo[]
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export interface ProductStageCronRequest {
  stageId: string
  deploymentCron: string
}

export interface ProductRequest {
  name: string
  description: string | null
  // Only valid for stages whose deploymentPolicy is SCHEDULED.
  // Omit/null = leave existing configuration unchanged; provide the full desired
  // list to replace it (an empty list clears all configured times).
  stageDeploymentCrons?: ProductStageCronRequest[] | null
}

export interface ProductStageCronInfo {
  stageId: string
  stageName: string
  deploymentCron: string
  nextDeploymentAt: string | null
}

export interface ProductResponse {
  id: string
  name: string
  description: string | null
  stageDeploymentCrons: ProductStageCronInfo[]
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export interface WorkloadStageInfo {
  id: string
  name: string
  description: string | null
}

export type KubernetesKind = 'DEPLOYMENT' | 'STATEFUL_SET'

export interface WorkloadRequest {
  name: string
  productId: string
  description: string | null
  kubernetes: boolean
  // Required when kubernetes is true, must be omitted/null otherwise.
  kubernetesKind?: KubernetesKind | null
  kubernetesNameSpace?: string | null
  // Only honored on update: create always links to every existing stage.
  // Omit/null = leave stage links unchanged, [] = unlink from all stages.
  stageIds?: string[] | null
}

export interface WorkloadResponse {
  id: string
  name: string
  productId: string
  description: string | null
  kubernetes: boolean
  kubernetesKind: KubernetesKind | null
  kubernetesNameSpace: string | null
  stages: WorkloadStageInfo[]
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export interface ReleaseRequest {
  // Immutable once created — the backend rejects any attempt to change it on update.
  binaryUrl: string
  description: string | null
  workloadId: string
}

export interface ReleaseStageInfo {
  id: string
  name: string
  order: number
}

export interface ReleaseResponse {
  id: string
  binaryUrl: string
  description: string | null
  workloadId: string
  currentStage: ReleaseStageInfo
  canPromote: boolean
  lastDeployedAt: string | null
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export interface ReleaseHistoryEntry {
  id: string
  binaryUrl: string
  stage: ReleaseStageInfo
  timestamp: string
  deployedAt: string | null
  createdBy: string
}
