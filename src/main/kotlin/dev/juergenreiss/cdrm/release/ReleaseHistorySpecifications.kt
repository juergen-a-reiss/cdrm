// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification

// Shared between the paginated release-history list (a Specification, below) and the
// chart's aggregation query (ReleaseHistoryAggregationRepository, built with the same
// CriteriaBuilder/Root directly) — so a filter behaves identically for both. A filter
// field left null contributes no predicate at all, rather than needing an "is null or"
// clause per column: simpler, and avoids the various JPA/Hibernate provider quirks
// around binding a null value to a collection-valued IN parameter.
fun releaseHistoryPredicates(cb: CriteriaBuilder, root: Path<ReleaseHistory>, filter: ReleaseHistoryFilter): List<Predicate> {
    val predicates = mutableListOf<Predicate>()
    filter.productIds?.let { predicates += root.get<Any>("productId").`in`(it) }
    filter.workloadIds?.let { predicates += root.get<Any>("workloadId").`in`(it) }
    filter.stageIds?.let { predicates += root.get<Any>("stageId").`in`(it) }
    filter.pipelines?.let { predicates += root.get<Any>("pipeline").`in`(it) }
    filter.actions?.let { predicates += root.get<Any>("action").`in`(it) }
    filter.since?.let { predicates += cb.greaterThanOrEqualTo(root.get("createdAt"), it) }
    filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { search ->
        val pattern = "%${search.lowercase()}%"
        predicates += cb.or(
            cb.like(cb.lower(root.get("productName")), pattern),
            cb.like(cb.lower(root.get("workloadName")), pattern),
            cb.like(cb.lower(root.get("stageName")), pattern),
            cb.like(cb.lower(root.get("image")), pattern),
        )
    }
    filter.allowedProductNames?.let { predicates += root.get<Any>("productName").`in`(it) }
    filter.allowedWorkloadNames?.let { predicates += root.get<Any>("workloadName").`in`(it) }
    return predicates
}

fun releaseHistorySpecification(filter: ReleaseHistoryFilter): Specification<ReleaseHistory> =
    Specification { root, _, cb -> cb.and(*releaseHistoryPredicates(cb, root, filter).toTypedArray()) }
