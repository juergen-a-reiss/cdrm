// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.cluster

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/clusters")
class ClusterController(private val service: ClusterService) {

    @GetMapping
    fun findAll(@RequestParam(required = false) sort: String?): List<ClusterResponse> = service.findAll(sort)

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): ClusterResponse = service.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: ClusterRequests): ClusterResponse = service.create(request)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: ClusterRequests): ClusterResponse = service.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)
}
