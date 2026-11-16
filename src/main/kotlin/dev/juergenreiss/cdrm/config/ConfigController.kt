// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.config

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/config")
class ConfigController(private val service: ConfigService) {

    @GetMapping
    fun findAll(): List<ConfigResponse> = service.findAll()

    @GetMapping("/{key}")
    fun findByKey(@PathVariable key: String): ConfigResponse = service.findByKey(key)

    @PutMapping("/{key}")
    fun upsert(@PathVariable key: String, @RequestBody request: ConfigUpdateRequest): ConfigResponse =
        service.upsert(key, request)
}
