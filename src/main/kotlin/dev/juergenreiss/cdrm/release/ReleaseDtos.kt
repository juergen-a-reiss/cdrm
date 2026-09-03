// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import java.time.Instant
import java.util.*

data class ReleaseRequest(
    val image: String,
    val description: String?,
    val workloadId: UUID,
    val commitId: String?,
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
    val commitId: String?,
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
    // Of the latest deployment to this release's current stage (regardless of which
    // action produced this response, unlike deployError above — a live/persisted value,
    // not scoped to the request that produced this response). Null while the deploy
    // hasn't succeeded yet, or a Kubernetes rollout is still within its verification
    // window; canPromote is false whenever this is null or deploymentFailed is true.
    val deploymentFinished: Instant?,
    val deploymentFailed: Boolean,
    // Reason deploymentFailed is true (e.g. "1 pod(s) restarting (restart count > 0)"),
    // so the frontend can show why without a separate history fetch. Null otherwise.
    val deploymentError: String?,
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
    // When the async rollout-verification check concluded, success or failure — set
    // eagerly (= deployedAt) for a non-Kubernetes workload. Null while deployedAt itself
    // is null, or while a Kubernetes rollout is still within its verification window.
    val deploymentFinished: Instant?,
    val deploymentFailed: Boolean,
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
    val deploymentFinished: Instant?,
    val deploymentFailed: Boolean,
    val createdBy: UUID,
)

// One page of the release-history dashboard's details table — the table is paginated
// at the database level (see ReleaseService.historyOverview), not fetched in full and
// paged in the browser.
data class ReleaseHistoryPageResponse(
    val content: List<ReleaseHistoryOverviewEntry>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)
