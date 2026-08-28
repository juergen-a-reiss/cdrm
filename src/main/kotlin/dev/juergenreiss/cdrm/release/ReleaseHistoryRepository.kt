// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReleaseHistoryRepository : JpaRepository<ReleaseHistory, UUID> {
    fun findByReleaseIdOrderByCreatedAtDesc(releaseId: UUID): List<ReleaseHistory>

    fun findTopByReleaseIdAndDeployedAtIsNotNullOrderByDeployedAtDesc(releaseId: UUID): ReleaseHistory?

    fun findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(stageId: UUID, releaseIds: Collection<UUID>): ReleaseHistory?

    // Locked so a second app instance (or a slow-running overlapping tick) can't
    // process the same pending row twice.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ReleaseHistory h where h.deployedAt is null")
    fun findPendingForUpdate(): List<ReleaseHistory>
}
