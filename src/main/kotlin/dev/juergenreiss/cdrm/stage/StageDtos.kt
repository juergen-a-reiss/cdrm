// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

import dev.juergenreiss.cdrm.cluster.ClusterType
import java.time.Instant
import java.util.*

data class StageRequest(
    val name: String,
    val description: String?,
    val order: Int,
    val deploymentPolicy: DeploymentPolicy,
    val kubernetesContext: String? = null,
    val namespacePrefix: String? = null,
    // Omit/null to leave the existing cluster links unchanged; provide the full
    // desired list to replace it (an empty list unlinks from all clusters).
    val clusterIds: List<UUID>? = null,
)

data class StageClusterInfo(
    val id: UUID,
    val name: String,
    val clusterType: ClusterType,
)

data class StageResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val order: Int,
    val deploymentPolicy: DeploymentPolicy,
    val kubernetesContext: String?,
    val namespacePrefix: String?,
    val clusters: List<StageClusterInfo>,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)
