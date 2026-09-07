// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

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
