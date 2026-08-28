// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface ReleaseRepository : JpaRepository<Release, UUID> {
    fun findByWorkloadIdAndCurrentStageId(workloadId: UUID, currentStageId: UUID): List<Release>
}