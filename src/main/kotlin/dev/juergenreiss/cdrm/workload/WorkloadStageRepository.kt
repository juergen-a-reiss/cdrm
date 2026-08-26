// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkloadStageRepository : JpaRepository<WorkloadStage, UUID> {
    fun findByWorkloadId(workloadId: UUID): List<WorkloadStage>
}
