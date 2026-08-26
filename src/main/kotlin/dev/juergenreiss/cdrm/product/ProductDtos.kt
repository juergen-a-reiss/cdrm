// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.*

data class ProductStageCronRequest(
    val stageId: UUID,
    val deploymentCron: String,
)

data class ProductRequest(
    val name: String,
    val description: String?,
    @Schema(
        description = "Deployment cron per stage — only valid for stages whose deploymentPolicy is SCHEDULED. " +
            "Omit/null to leave the existing configuration unchanged; provide the full desired list to replace " +
            "it (an empty list clears all configured times).",
        nullable = true,
    )
    val stageDeploymentCrons: List<ProductStageCronRequest>? = null,
)

data class ProductStageCronInfo(
    val stageId: UUID,
    val stageName: String,
    val deploymentCron: String,
    val nextDeploymentAt: Instant?,
)

data class ProductResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val stageDeploymentCrons: List<ProductStageCronInfo>,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)
