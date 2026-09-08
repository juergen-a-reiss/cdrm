// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/audit")
class AuditController(private val service: AuditService) {

    @GetMapping
    fun findAll(
        @RequestParam(required = false) sort: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(name = "entityType", required = false) entityTypes: List<AuditEntityType>?,
        @RequestParam(name = "action", required = false) actions: List<AuditAction>?,
        @RequestParam(required = false) search: String?,
    ): AuditPageResponse = service.findAll(sort, page, size, entityTypes, actions, search)
}
