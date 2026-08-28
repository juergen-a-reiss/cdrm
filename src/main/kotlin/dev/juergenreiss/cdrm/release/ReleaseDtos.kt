// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import java.time.Instant
import java.util.*

data class ReleaseRequest(
    val binaryUrl: String,
    val description: String?,
    val workloadId: UUID,
)

data class RedeployRequest(
    val stageId: UUID,
)

data class ReleaseResponse(
    val id: UUID,
    val binaryUrl: String,
    val description: String?,
    val workloadId: UUID,
    val currentStage: ReleaseStageInfo,
    val canPromote: Boolean,
    val canRollback: Boolean,
    // The current stage (only if this release is head there) plus every stage before it —
    // the valid targets for redeploy().
    val redeployableStages: List<ReleaseStageInfo>,
    val lastDeployedAt: Instant?,
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
    val binaryUrl: String,
    val stage: ReleaseStageInfo,
    val timestamp: Instant,
    val deployedAt: Instant?,
    val createdBy: UUID,
)
