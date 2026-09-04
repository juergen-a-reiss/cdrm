// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

export type DeploymentPolicy = 'IMMEDIATE' | 'SCHEDULED'

export type ClusterType = 'K8S' | 'PROXMOX'

export interface K8sNamespaceGitopsConfig {
  namespace: string
  useGitOps: boolean
  fileExpression: string | null
  yamlExpression: string | null
  // Null means "use the cluster-wide K8sGitopsConfig.gitBranch".
  gitBranch: string | null
}

export interface K8sGitopsConfig {
  useGitOps: boolean
  gitRepo: string
  gitBranch: string
  // Keyed by namespace name (matching an entry in k8sNamespaces).
  namespaces: Record<string, K8sNamespaceGitopsConfig>
}

export interface ClusterRequest {
  name: string
  description: string | null
  clusterType: ClusterType
  url: string
  k8sNamespaces?: string | null
  k8sGitOpsConfig?: K8sGitopsConfig | null
}

export interface ClusterResponse {
  id: string
  name: string
  description: string | null
  clusterType: ClusterType
  url: string
  k8sNamespaces: string | null
  k8sGitOpsConfig: K8sGitopsConfig | null
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
  pipeline: string
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
  pipeline: string
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
  isGroup: boolean
  // Must reference an existing product whose isGroup is true. Null clears grouping.
  productGroupId?: string | null
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
  isGroup: boolean
  productGroupId: string | null
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
  // Must match an existing stage's pipeline. On create, the workload links to every
  // stage of this pipeline. On update, every id in stageIds (or, if omitted, every
  // already-linked stage) must belong to this pipeline.
  pipeline: string
  // Only honored on update: create always links to every stage of the given pipeline.
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
  pipeline: string
  stages: WorkloadStageInfo[]
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export interface ReleaseRequest {
  // Immutable once created — the backend rejects any attempt to change it on update.
  image: string
  description: string | null
  workloadId: string
  // The git commit (or other VCS revision) this image was built from. Also immutable
  // once created, same as image.
  commitId: string | null
}

export interface RedeployRequest {
  stageId: string
}

export interface ReleaseStageInfo {
  id: string
  name: string
  order: number
}

export interface ReleaseResponse {
  id: string
  image: string
  description: string | null
  workloadId: string
  currentStage: ReleaseStageInfo
  commitId: string | null
  canPromote: boolean
  canRollback: boolean
  canEdit: boolean
  canDelete: boolean
  // The current stage (only if this release is head there) plus every stage before it,
  // minus any the caller's cdrm-release-actions claim disallows redeploy to.
  redeployableStages: ReleaseStageInfo[]
  lastDeployedAt: string | null
  // Set only when this response is the direct result of an action (create/promote/
  // rollback/redeploy) whose synchronous immediate deploy attempt just failed — null on
  // a plain list/get read, and null when the deploy succeeded or the stage is
  // SCHEDULED-policy (nothing attempted synchronously).
  deployError: string | null
  // Of the latest deployment to this release's current stage — a live/persisted value,
  // not scoped to this request (unlike deployError above). Null while the deploy hasn't
  // succeeded yet, or a Kubernetes rollout is still within its verification window;
  // canPromote is false whenever this is null or deploymentFailed is true.
  deploymentFinished: string | null
  deploymentFailed: boolean
  // Reason deploymentFailed is true. Null otherwise.
  deploymentError: string | null
  createdAt: string
  modifiedAt: string
  createdBy: string
  modifiedBy: string
}

export type ReleaseHistoryAction = 'CREATED' | 'PROMOTED' | 'ROLLED_BACK' | 'REDEPLOYED'

export interface ReleaseHistoryEntry {
  id: string
  image: string
  action: ReleaseHistoryAction
  stage: ReleaseStageInfo
  timestamp: string
  deployedAt: string | null
  // The SCHEDULED-policy trigger time this entry is waiting on. Null once deployed, for
  // IMMEDIATE-policy stages, or if no cron is configured for this (product, stage).
  scheduledAt: string | null
  // Reason the most recent deploy attempt for this entry failed (e.g. "cluster not
  // reachable"). Null once deployed, or while still unattempted.
  deployError: string | null
  // When the async rollout-verification check concluded, success or failure — set
  // eagerly (= deployedAt) for a non-Kubernetes workload. Null while deployedAt itself
  // is null, or while a Kubernetes rollout is still within its verification window.
  deploymentFinished: string | null
  deploymentFailed: boolean
  createdBy: string
}

// productId/productName/workloadName/stage.name are a snapshot taken when the entry was
// recorded, so they stay populated even after the product/workload/stage that produced
// them is later deleted. workloadId stays nullable only for parity with the backing
// entity field.
export interface ReleaseHistoryOverviewEntry {
  id: string
  releaseId: string
  image: string
  action: ReleaseHistoryAction
  productId: string
  productName: string
  workloadId: string | null
  workloadName: string
  stage: ReleaseStageInfo
  timestamp: string
  deployedAt: string | null
  scheduledAt: string | null
  deployError: string | null
  deploymentFinished: string | null
  deploymentFailed: boolean
  createdBy: string
}

// One page of the release-history dashboard's details table — sorted, filtered, and
// paginated by the backend (see releasesApi.historyOverview), not fetched in full.
export interface ReleaseHistoryPageResponse {
  content: ReleaseHistoryOverviewEntry[]
  totalElements: number
  page: number
  size: number
}

export type ReleaseHistoryGroupBy = 'ACTION' | 'PRODUCT' | 'WORKLOAD' | 'STAGE'

// One (month, key) bucket of the dashboard chart's data — counted by the database, not
// tallied client-side from the full entry list.
export interface ReleaseHistorySummaryEntry {
  month: string
  key: string
  count: number
}

// The filter/search parameters shared by releasesApi.historyOverview and
// historySummary — the backend applies them identically to the table and the chart.
export interface ReleaseHistoryFilterParams {
  productIds?: string[]
  workloadIds?: string[]
  stageIds?: string[]
  pipelines?: string[]
  actions?: ReleaseHistoryAction[]
  monthsBack?: number
  search?: string
}
