// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

// allowedProductNames/allowedWorkloadNames null means the caller's cdrm-products/
// cdrm-workloads ReBAC claim isn't set (or the caller is cdrm-devops, exempt from ReBAC
// entirely) — see AuditSpecifications for how these apply only to PRODUCT/WORKLOAD rows,
// never to CLUSTER/STAGE/CONFIG ones.
data class AuditFilter(
    val entityTypes: Set<AuditEntityType>? = null,
    val actions: Set<AuditAction>? = null,
    val search: String? = null,
    val allowedProductNames: Set<String>? = null,
    val allowedWorkloadNames: Set<String>? = null,
)
