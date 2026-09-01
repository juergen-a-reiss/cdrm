// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import java.time.Instant
import java.util.*

data class ReleaseRequest(
    val image: String,
    val description: String?,
    val workloadId: UUID,
)

data class RedeployRequest(
    val stageId: UUID,
)

data class ReleaseResponse(
    val id: UUID,
    val image: String,
    val description: String?,
    val workloadId: UUID,
    val currentStage: ReleaseStageInfo,
    val canPromote: Boolean,
    val canRollback: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    // The current stage (only if this release is head there) plus every stage before it,
    // minus any the caller's cdrm-release-actions claim disallows redeploy to — the valid
    // targets for redeploy().
    val redeployableStages: List<ReleaseStageInfo>,
    val lastDeployedAt: Instant?,
    // Set only when this response is the direct result of an action (create/promote/
    // rollback/redeploy) whose synchronous IMMEDIATE-policy deploy attempt just failed —
    // null on a plain findAll()/findById() read, and null when the deploy succeeded or
    // the stage is SCHEDULED-policy (nothing attempted synchronously).
    val deployError: String?,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)

data class ReleaseStageInfo(
    val id: UUID,
    val name: String,
    val order: Int,
)

data class ReleaseHistoryEntry(
    val id: UUID,
    val image: String,
    val action: ReleaseHistoryAction,
    val stage: ReleaseStageInfo,
    val timestamp: Instant,
    val deployedAt: Instant?,
    // The SCHEDULED-policy trigger time this entry is waiting on, so the UI can render
    // "Pending (<time>)" instead of a bare "Pending". Null once deployed, for
    // IMMEDIATE-policy stages (retried every tick, no fixed time), or if no cron is
    // configured for this (product, stage).
    val scheduledAt: Instant?,
    // Reason the most recent deploy attempt for this entry failed (e.g. "cluster not
    // reachable"), so the UI can render "Pending (<reason>)". Null once deployed, or
    // while still unattempted.
    val deployError: String?,
    val createdBy: UUID,
)

// productId/productName/workloadName/stage.name are a snapshot taken when the entry was
// recorded, so they stay populated even after the product/workload/stage that produced
// them is later deleted. workloadId stays nullable only for parity with the entity field
// it's read from (release_history.workload_id).
data class ReleaseHistoryOverviewEntry(
    val id: UUID,
    val releaseId: UUID,
    val image: String,
    val action: ReleaseHistoryAction,
    val productId: UUID,
    val productName: String,
    val workloadId: UUID?,
    val workloadName: String,
    val stage: ReleaseStageInfo,
    val timestamp: Instant,
    val deployedAt: Instant?,
    val scheduledAt: Instant?,
    val deployError: String?,
    val createdBy: UUID,
)
