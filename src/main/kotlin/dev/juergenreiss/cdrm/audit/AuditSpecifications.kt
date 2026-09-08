// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification

fun auditPredicates(cb: CriteriaBuilder, root: Path<AuditEntry>, filter: AuditFilter): List<Predicate> {
    val predicates = mutableListOf<Predicate>()
    filter.entityTypes?.let { predicates += root.get<Any>("entityType").`in`(it) }
    filter.actions?.let { predicates += root.get<Any>("action").`in`(it) }
    filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { search ->
        predicates += cb.like(cb.lower(root.get("entityName")), "%${search.lowercase()}%")
    }
    // ReBAC (see README): cdrm-products/cdrm-workloads restrict only PRODUCT/WORKLOAD
    // rows — a CLUSTER/STAGE/CONFIG row carries no product/workload association and
    // stays visible to every caller, same as those entities' own general read access.
    // Skipped entirely (no predicate at all) when both are null: the caller is either
    // cdrm-devops (exempt from ReBAC) or simply has neither claim set, in which case
    // PRODUCT/WORKLOAD rows are unrestricted too — matching RebacContext.canSeeProduct/
    // canSeeWorkload's own "claim absent = ReBAC doesn't apply" semantics.
    if (filter.allowedProductNames != null || filter.allowedWorkloadNames != null) {
        val entityType = root.get<AuditEntityType>("entityType")
        val notProductOrWorkload = entityType.`in`(AuditEntityType.PRODUCT, AuditEntityType.WORKLOAD).not()
        val productAllowed = cb.and(
            cb.equal(entityType, AuditEntityType.PRODUCT),
            filter.allowedProductNames?.let { root.get<Any>("entityName").`in`(it) } ?: cb.conjunction(),
        )
        val workloadAllowed = cb.and(
            cb.equal(entityType, AuditEntityType.WORKLOAD),
            filter.allowedProductNames?.let { root.get<Any>("productName").`in`(it) } ?: cb.conjunction(),
            filter.allowedWorkloadNames?.let { root.get<Any>("entityName").`in`(it) } ?: cb.conjunction(),
        )
        predicates += cb.or(notProductOrWorkload, productAllowed, workloadAllowed)
    }
    return predicates
}

fun auditSpecification(filter: AuditFilter): Specification<AuditEntry> =
    Specification { root, _, cb -> cb.and(*auditPredicates(cb, root, filter).toTypedArray()) }
