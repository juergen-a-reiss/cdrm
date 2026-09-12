// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import dev.juergenreiss.cdrm.release.ReleaseHistoryOverviewEntry
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.workload.KubernetesKind
import java.util.UUID

data class ProductStageWorkloadOverview(
    val workloadId: UUID,
    val workloadName: String,
    // The workload's own owning product — for a group's overview this can differ per row
    // (workloads are pooled from the group and every descendant product/subgroup), so the
    // frontend needs it to label which product each row belongs to.
    val productId: UUID,
    val productName: String,
    val kubernetes: Boolean,
    val kubernetesKind: KubernetesKind?,
    // stage.effectiveNamespaceFor(workload) — null when the workload has no configured
    // Kubernetes namespace.
    val namespace: String?,
    // The latest deploy/rollback/redeploy recorded at THIS stage — null if this
    // workload has never been deployed here at all.
    val latestRelease: ReleaseHistoryOverviewEntry?,
)

// One tab of the product detail view — a stage the product's workloads (collectively,
// across however many different pipelines they span) are actually linked to.
data class ProductStageOverview(
    val stageId: UUID,
    val stageName: String,
    val pipeline: String,
    val order: Int,
    // The kubeconfig context name — "the cluster" for this stage, resolved the same way
    // a real deploy/verify does (see stage/EffectiveNamespace.kt's callers). Not derived
    // from stage_cluster, which is display-only elsewhere in the app.
    val kubernetesContext: String?,
    val deploymentPolicy: DeploymentPolicy,
    val workloads: List<ProductStageWorkloadOverview>,
)

data class ProductDeploymentOverviewResponse(
    val productId: UUID,
    val productName: String,
    // Whether productId names a product group — the frontend only needs to show the
    // per-row Product column (see ProductStageWorkloadOverview.productName) when true,
    // since a plain product's workloads are all its own.
    val isGroup: Boolean,
    val stages: List<ProductStageOverview>,
)
