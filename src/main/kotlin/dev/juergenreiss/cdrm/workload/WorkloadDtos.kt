// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.*

data class WorkloadRequest(
    val name: String,
    val productId: UUID,
    val description: String?,
    val kubernetes: Boolean,
    @Schema(description = "Required when kubernetes is true, must be omitted/null otherwise.")
    val kubernetesKind: KubernetesKind? = null,
    @Schema(description = "Required when kubernetes is true, must be omitted/null otherwise.")
    val kubernetesNameSpace: String? = null,
    val pipeline: String,
    @Schema(
        description = "Stage ids this workload should be linked to. Only honored on update — " +
            "create always links to every existing stage. Omit or set to null to leave the current " +
            "links unchanged; pass an empty array to unlink from all stages.",
        nullable = true,
    )
    val stageIds: List<UUID>? = null,
)

data class WorkloadResponse(
    val id: UUID,
    val name: String,
    val productId: UUID,
    val description: String?,
    val kubernetes: Boolean,
    val kubernetesKind: KubernetesKind?,
    val kubernetesNameSpace: String?,
    val pipeline: String,
    val stages: List<WorkloadStageInfo>,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)

data class WorkloadStageInfo(
    val id: UUID,
    val name: String,
    val description: String?,
)
