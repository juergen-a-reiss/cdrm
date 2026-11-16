// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

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
