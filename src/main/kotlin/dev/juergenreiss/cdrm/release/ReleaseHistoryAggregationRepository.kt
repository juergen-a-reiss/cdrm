// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneId

enum class ReleaseHistoryGroupBy { ACTION, PRODUCT, WORKLOAD, STAGE }

data class ReleaseHistorySummaryEntry(
    val month: String,
    val key: String,
    val count: Long,
)

// The chart's data: counts of release_history rows per (month, groupBy dimension),
// computed by the database — the alternative (fetch every matching row and tally it in
// the browser, which is what this replaced) doesn't scale as release_history grows.
// Uses the JPA Criteria API directly, not a derived/@Query method, since the grouping
// column varies by `groupBy` and Spring Data has no native way to express that.
@Repository
class ReleaseHistoryAggregationRepository(private val entityManager: EntityManager) {

    fun summary(filter: ReleaseHistoryFilter, groupBy: ReleaseHistoryGroupBy): List<ReleaseHistorySummaryEntry> {
        val cb = entityManager.criteriaBuilder
        val query = cb.createTupleQuery()
        val root = query.from(ReleaseHistory::class.java)

        val month = cb.function("date_trunc", Instant::class.java, cb.literal("month"), root.get<Instant>("createdAt"))
        val key = when (groupBy) {
            ReleaseHistoryGroupBy.ACTION -> root.get<ReleaseHistoryAction>("action")
            ReleaseHistoryGroupBy.PRODUCT -> root.get<String>("productName")
            ReleaseHistoryGroupBy.WORKLOAD -> root.get<String>("workloadName")
            ReleaseHistoryGroupBy.STAGE -> root.get<String>("stageName")
        }

        query.multiselect(month, key, cb.count(root))
        query.where(*releaseHistoryPredicates(cb, root, filter).toTypedArray())
        query.groupBy(month, key)
        query.orderBy(cb.asc(month))

        return entityManager.createQuery(query).resultList.map(::toSummaryEntry)
    }

    private fun toSummaryEntry(tuple: Tuple): ReleaseHistorySummaryEntry {
        val month = (tuple[0] as Instant).atZone(ZoneId.systemDefault())
        return ReleaseHistorySummaryEntry(
            month = "%04d-%02d".format(month.year, month.monthValue),
            key = tuple[1].toString(),
            count = tuple[2] as Long,
        )
    }
}
