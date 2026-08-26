// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StageClusterRepository : JpaRepository<StageCluster, UUID> {
    fun findByStageId(stageId: UUID): List<StageCluster>
}
