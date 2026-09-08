// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import dev.juergenreiss.cdrm.common.SortSpec
import dev.juergenreiss.cdrm.security.RebacContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper

// Read-only: audit entries are never created, updated, or deleted through the API — see
// AuditRecorder for how they're written (internally, by the entity services themselves).
@Service
@Transactional(readOnly = true)
class AuditService(
    private val repository: AuditEntryRepository,
    private val rebac: RebacContext,
) {

    private val objectMapper = jacksonObjectMapper()

    private val defaultSort = SortSpec("createdAt", descending = true)
    private val sortKeys: Map<String, String> = mapOf(
        "createdAt" to "createdAt",
        "entityType" to "entityType",
        "entityName" to "entityName",
        "action" to "action",
        "createdBy" to "createdBy",
    )

    fun findAll(
        sort: String? = null,
        page: Int = 0,
        size: Int = 25,
        entityTypes: List<AuditEntityType>? = null,
        actions: List<AuditAction>? = null,
        search: String? = null,
    ): AuditPageResponse {
        val spec = SortSpec.parse(sort, defaultSort)
        val property = sortKeys[spec.key] ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sort key '${spec.key}'")
        val direction = if (spec.descending) Sort.Direction.DESC else Sort.Direction.ASC
        val filter = AuditFilter(
            entityTypes = entityTypes?.toSet(),
            actions = actions?.toSet(),
            search = search,
            allowedProductNames = if (rebac.isDevops) null else rebac.allowedProducts,
            allowedWorkloadNames = if (rebac.isDevops) null else rebac.allowedWorkloads,
        )
        val pageable = PageRequest.of(page, size, Sort.by(direction, property))
        val result = repository.findAll(auditSpecification(filter), pageable)
        return AuditPageResponse(
            content = result.content.map { it.toResponse() },
            totalElements = result.totalElements,
            page = page,
            size = size,
        )
    }

    private fun AuditEntry.toResponse() = AuditEntryResponse(
        id = id!!,
        entityType = entityType,
        entityId = entityId,
        entityName = entityName,
        productName = productName,
        action = action,
        newState = newState?.let { objectMapper.readValue(it, Any::class.java) },
        changes = changes?.let { objectMapper.readValue(it, Any::class.java) },
        createdAt = createdAt!!,
        createdBy = createdBy,
    )
}
