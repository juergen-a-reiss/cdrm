// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

export type DeploymentPolicy = 'IMMEDIATE' | 'SCHEDULED'

export type ClusterType = 'K8S' | 'PROXMOX'

// SIMPLE: fileExpression/yamlExpression, substituted with {namespace}/{workload} and set
// to the new release's image — exactly one file, one YAML key, one value per deploy.
// TEMPLATE: templateScript computes an arbitrary list of (branch, file, YAML key, value)
// edits instead, in a sandboxed GraalVM JS engine (see the backend's
// GitOpsTemplateEngine for exactly what it can reference and must return).
export type GitOpsNamespaceMode = 'SIMPLE' | 'TEMPLATE'

// Powers the TEMPLATE-mode script editor's "Test" button — script is whatever is
// currently in the editor's buffer (not what's saved on the cluster); workloadId/stageId
// stand in for a real deploy's (workload, stage) pair to supply the rest of the context
// variables (see the backend's GitOpsTemplateContext); repositoryUrl is whatever the
// form currently resolves for this namespace (its own gitRepo override, or the cluster's
// default).
export interface GitOpsTemplateTestRequest {
  script: string
  repositoryUrl: string
  clusterName: string
  workloadId: string
  stageId: string
  releaseBinary: string
}

export interface GitOpsTemplateTestEdit {
  gitBranch: string
  filePath: string
  yamlKeyPath: string
  value: string
  branchExists: boolean
  fileExists: boolean
  yamlKeyPathExists: boolean
}

// success=false means the script itself failed or returned no edits (reason explains
// why, edits is then empty); success=true means it returned at least one edit, each
// annotated with whether it would actually apply against the real git repository.
export interface GitOpsTemplateTestResponse {
  success: boolean
  reason: string | null
  edits: GitOpsTemplateTestEdit[]
}

export interface K8sNamespaceGitopsConfig {
  namespace: string
  useGitOps: boolean
  mode: GitOpsNamespaceMode
  fileExpression: string | null
  yamlExpression: string | null
  // Null means "use the cluster-wide K8sGitopsConfig.gitBranch". Only consulted for
  // mode=SIMPLE — a TEMPLATE script returns its own branch per edit instead.
  gitBranch: string | null
  // Null means "use the cluster-wide K8sGitopsConfig.gitRepo". Must be one of
  // gitOpsApi.repositories()'s urls, same as the cluster-wide one. Applies to both
  // modes — the template still commits to this one resolved repo.
  gitRepo: string | null
  // GraalVM JS source, wrapped and invoked as a function body (so a plain top-level
  // `return [...]` works) — only used, and only required, when mode=TEMPLATE.
  templateScript: string | null
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

// Keys assignable in the "menu-visibility" config entry — must match the `key` of each
// entry in App.vue's navItems. "configuration" is deliberately excluded: it's hardcoded
// visible to cdrm-devops only (see the backend's MenuVisibilityService), never editable.
export type AssignableMenuKey =
  | 'clusters'
  | 'stages'
  | 'products'
  | 'workloads'
  | 'releases'
  | 'release-history'
  | 'audit'

export type MenuVisibilityConfig = Record<string, AssignableMenuKey[]>

export interface ConfigResponse {
  key: string
  value: unknown
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

// See docs/deployment-status-state-machine.odg — release_history's two independent
// tracks. NOT_APPLICABLE means the respective UI column should be hidden entirely for
// this row, not shown empty (it's not GitOps-managed / not a Kubernetes workload).
export type GitOpsStatus = 'NOT_APPLICABLE' | 'PENDING' | 'PUSH_SUCCEEDED' | 'PUSH_FAILED_RETRYING' | 'PUSH_FAILED'

export type KubernetesStatus =
  | 'NOT_APPLICABLE'
  | 'NOT_STARTED'
  | 'AWAITING_CLUSTER_SYNC'
  | 'ROLLING_OUT'
  | 'HEALTHY'
  | 'FAILED'
  | 'REPLACED'

export interface ReleaseResponse {
  id: string
  image: string
  description: string | null
  workloadId: string
  currentStage: ReleaseStageInfo
  commitId: string | null
  // Whether the pipeline even has a stage after the current one — independent of
  // canPromote (which also folds in permission and deployment-completion): distinguishes
  // "already at the final stage" (hide the promote control) from "blocked for some other
  // reason" (show it disabled, with a reason).
  hasNextStage: boolean
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
  gitOpsStatus: GitOpsStatus
  gitopsError: string | null
  kubernetesStatus: KubernetesStatus
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
  gitOpsStatus: GitOpsStatus
  gitopsError: string | null
  kubernetesStatus: KubernetesStatus
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
  gitOpsStatus: GitOpsStatus
  gitopsError: string | null
  kubernetesStatus: KubernetesStatus
  createdBy: string
}

// The product detail view's per-stage tabs — see productsApi.deploymentOverview. Only
// ever loaded from Postgres, never touches Kubernetes (see LiveStatusResponse below for
// that, fetched separately and lazily per row).
export interface ProductStageWorkloadOverview {
  workloadId: string
  workloadName: string
  kubernetes: boolean
  kubernetesKind: KubernetesKind | null
  // stage.namespacePrefix + the workload's own kubernetesNameSpace — null if the
  // workload has no Kubernetes namespace configured.
  namespace: string | null
  // The latest deploy/rollback/redeploy recorded at THIS stage — null if this workload
  // has never been deployed here.
  latestRelease: ReleaseHistoryOverviewEntry | null
}

export interface ProductStageOverview {
  stageId: string
  stageName: string
  pipeline: string
  order: number
  // The kubeconfig context name — "the cluster" for this stage (not derived from the
  // stage_cluster admin-UI link, which the backend never consults for a live/deploy
  // read either).
  kubernetesContext: string | null
  deploymentPolicy: DeploymentPolicy
  workloads: ProductStageWorkloadOverview[]
}

export interface ProductDeploymentOverviewResponse {
  productId: string
  productName: string
  // One tab per stage any of the product's workloads is linked to — a product's
  // workloads can span multiple pipelines, so this is a union across all of them, not
  // one fixed pipeline's stage list.
  stages: ProductStageOverview[]
}

// NOT_APPLICABLE: non-Kubernetes workload, or the stage/workload is missing the
// Kubernetes config a live read needs. NOT_FOUND: never deployed here (or deleted
// outside cdrm). UNREACHABLE: the live read itself failed — error explains why. OK: a
// real read succeeded, every field below is populated.
export type LiveStatusState = 'NOT_APPLICABLE' | 'NOT_FOUND' | 'UNREACHABLE' | 'OK'

export interface LivePodInfo {
  name: string
  image: string | null
  ready: boolean
  restartCount: number
  runningSince: string | null
}

// Live current state of a workload's Deployment/StatefulSet at a given stage, read
// on-demand from the cluster (see workloadsApi.liveStatus) — fetched lazily, per row,
// only after the base grid (ProductDeploymentOverviewResponse) has already rendered.
export interface LiveStatusResponse {
  state: LiveStatusState
  error: string | null
  desiredReplicas: number | null
  readyReplicas: number | null
  totalRestartCount: number | null
  // The manifest's own declared image — can differ from what's actually running on a
  // pod mid-rollout (see pods for that).
  image: string | null
  resourceCreatedAt: string | null
  // The oldest still-running pod's own start time — a single crashed-and-restarted
  // replica among otherwise-stable ones shouldn't reset this to "just now" (that pod's
  // own restart is still visible via its row in pods).
  uptimeSince: string | null
  pods: LivePodInfo[]
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

// Never RELEASE — a release's own lifecycle is already fully covered by
// release_history/ReleaseHistoryOverviewEntry; audit only exists for the entities that
// had no history trail at all.
export type AuditEntityType = 'CLUSTER' | 'STAGE' | 'PRODUCT' | 'WORKLOAD' | 'CONFIG'

export type AuditAction = 'CREATED' | 'UPDATED' | 'DELETED'

export interface AuditFieldChange {
  old: unknown
  new: unknown
}

export interface AuditEntryResponse {
  id: string
  entityType: AuditEntityType
  entityId: string
  entityName: string
  // Only set for WORKLOAD rows (the workload's owning product).
  productName: string | null
  action: AuditAction
  // Full entity snapshot after the action. Null for DELETED.
  newState: Record<string, unknown> | null
  // Field-level diff, keyed by field name. Every key of newState is "new" for CREATED,
  // every key of the prior state is "removed" (new: null) for DELETED.
  changes: Record<string, AuditFieldChange> | null
  createdAt: string
  createdBy: string
}

// One page of the audit log — sorted, filtered, and paginated by the backend (see
// auditApi.findAll), not fetched in full. ReBAC (cdrm-products/cdrm-workloads) is also
// applied backend-side, not here.
export interface AuditPageResponse {
  content: AuditEntryResponse[]
  totalElements: number
  page: number
  size: number
}

export interface AuditFilterParams {
  entityTypes?: AuditEntityType[]
  actions?: AuditAction[]
  search?: string
}

// NONE stores no user identifier at all (every created_by/modified_by, and every audit
// row's createdBy, becomes a fixed placeholder instead of the real user) — for a company
// that doesn't want to store even a pseudonymous identifier. USER_UUID is the existing,
// unchanged default behavior.
export type UserIdStorageMode = 'NONE' | 'USER_UUID'

// Only meaningful when mode is USER_UUID — how a resolved user id is rendered wherever
// a "By" column shows one (see usersApi.displayNames, which already applies this
// server-side — the frontend never needs to format anything itself).
export type UserDisplayFormat = 'UUID' | 'FIRSTNAME_LASTNAME_EMAIL' | 'LASTNAME_FIRSTNAME_EMAIL' | 'EMAIL'

export interface UserIdStorageConfig {
  mode: UserIdStorageMode
  displayFormat: UserDisplayFormat
}
