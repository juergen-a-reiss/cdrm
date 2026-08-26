// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

import java.time.Instant
import java.util.*

data class StageRequest(
    val name: String,
    val description: String?,
    val order: Int,
    val deploymentPolicy: DeploymentPolicy,
    val kubernetesContext: String? = null,
    val namespacePrefix: String? = null,
)

data class StageResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val order: Int,
    val deploymentPolicy: DeploymentPolicy,
    val kubernetesContext: String?,
    val namespacePrefix: String?,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)
