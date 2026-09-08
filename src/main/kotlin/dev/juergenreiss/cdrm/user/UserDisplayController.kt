// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/users")
class UserDisplayController(private val service: UserDisplayService) {

    // Any authenticated user can resolve any id — this is a company-directory-style
    // lookup, not a per-entity permission: the createdBy/modifiedBy ids a caller
    // resolves here are ones they can already see via whatever entity carried them,
    // and ReBAC (see README) already gated that entity's visibility upstream.
    @GetMapping("/display-names")
    fun displayNames(@RequestParam id: List<UUID>): Map<UUID, String> = service.resolve(id.toSet())
}
