// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/menu-visibility")
class MenuVisibilityController(private val service: MenuVisibilityService) {

    @GetMapping
    fun effective(): Set<String> = service.effectiveMenuKeys()
}
