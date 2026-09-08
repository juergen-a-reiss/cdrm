// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import java.time.Instant
import java.util.*

data class AuditEntryResponse(
    val id: UUID,
    val entityType: AuditEntityType,
    val entityId: String,
    val entityName: String,
    val productName: String?,
    val action: AuditAction,
    // Deserialized JSON (object/array/scalar), not a re-escaped string — same approach
    // as ConfigResponse.value.
    val newState: Any?,
    val changes: Any?,
    val createdAt: Instant,
    val createdBy: UUID,
)

data class AuditPageResponse(
    val content: List<AuditEntryResponse>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)
