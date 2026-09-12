// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import dev.juergenreiss.cdrm.release.ReleaseHistoryOverviewEntry
import java.util.UUID

// One row of the workload detail view's table — a stage this workload is linked to.
data class WorkloadStageDeploymentOverview(
    val stageId: UUID,
    val stageName: String,
    val order: Int,
    // stage.effectiveNamespaceFor(workload) — null when the workload has no configured
    // Kubernetes namespace.
    val namespace: String?,
    // The latest deploy/rollback/redeploy recorded at THIS stage — null if this workload
    // has never been deployed here at all.
    val latestRelease: ReleaseHistoryOverviewEntry?,
)

// Backs the workload detail view — see ProductDeploymentOverviewResponse for the
// per-product analogue. Deliberately excludes any live Kubernetes read (see
// WorkloadService.liveStatus for that), so it stays fast regardless of cluster
// reachability; the frontend fetches live status separately, per row.
data class WorkloadDeploymentOverviewResponse(
    val workloadId: UUID,
    val workloadName: String,
    val pipeline: String,
    val productId: UUID,
    val productName: String,
    // Whether a live-status read is even meaningful for this workload — a plain
    // (non-Kubernetes) workload has no cluster resource to read one from.
    val kubernetes: Boolean,
    val stages: List<WorkloadStageDeploymentOverview>,
)
