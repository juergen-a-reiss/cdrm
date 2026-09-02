// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant
import java.util.UUID

interface ReleaseIdAndLastDeployedAt {
    val releaseId: UUID
    val lastDeployedAt: Instant
}

// Every optional filter the release-history dashboard (table + chart) offers — null
// means "not filtering on this", matching the frontend's "no selection = no filter"
// convention for its multiselect filter bars. Shared between the paginated list query
// and the chart's aggregation query so both stay consistent with each other.
data class ReleaseHistoryFilter(
    val productIds: Set<UUID>? = null,
    val workloadIds: Set<UUID>? = null,
    val stageIds: Set<UUID>? = null,
    val pipelines: Set<String>? = null,
    val actions: Set<ReleaseHistoryAction>? = null,
    val since: Instant? = null,
    // Case-insensitive substring match against product/workload/stage name and image —
    // the dashboard's free-text search box. Was a client-side filter over the (fully
    // fetched) entry list; now that the table is paginated at the database level, a
    // client-side search would only ever search the current page, so this moved here
    // with it.
    val search: String? = null,
    // ReBAC (cdrm-products/cdrm-workloads) — not a user-chosen filter, but applied the
    // same way, so a restricted caller can't see more via the chart's aggregate counts
    // than they can via the table itself. Null (unset claim / cdrm-devops) means
    // unrestricted, same as everywhere else ReBAC is checked.
    val allowedProductNames: Set<String>? = null,
    val allowedWorkloadNames: Set<String>? = null,
)

interface ReleaseHistoryRepository : JpaRepository<ReleaseHistory, UUID>, JpaSpecificationExecutor<ReleaseHistory> {
    fun findByReleaseIdOrderByCreatedAtDesc(releaseId: UUID): List<ReleaseHistory>

    fun findTopByReleaseIdAndDeployedAtIsNotNullOrderByDeployedAtDesc(releaseId: UUID): ReleaseHistory?

    fun findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(stageId: UUID, releaseIds: Collection<UUID>): ReleaseHistory?

    // One query for every release in the list, instead of one per release — used to
    // sort ReleaseService.findAll() by "last deployed" without an N+1.
    @Query(
        "select h.releaseId as releaseId, max(h.deployedAt) as lastDeployedAt from ReleaseHistory h " +
            "where h.releaseId in :releaseIds and h.deployedAt is not null group by h.releaseId"
    )
    fun findLastDeployedAtByReleaseIdIn(releaseIds: Collection<UUID>): List<ReleaseIdAndLastDeployedAt>

    // Locked so a second app instance (or a slow-running overlapping tick) can't
    // process the same pending row twice.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ReleaseHistory h where h.deployedAt is null")
    fun findPendingForUpdate(): List<ReleaseHistory>
}
