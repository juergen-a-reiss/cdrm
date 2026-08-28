// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/releases")
class ReleaseController(private val service: ReleaseService) {
    @GetMapping
    fun findAll(): List<ReleaseResponse> = service.findAll()

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): ReleaseResponse = service.findById(id)

    @GetMapping("/{id}/history")
    fun history(@PathVariable id: UUID): List<ReleaseHistoryEntry> = service.history(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: ReleaseRequest): ReleaseResponse = service.create(request)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: ReleaseRequest): ReleaseResponse = service.update(id, request)

    @PostMapping("/{id}/promote")
    fun promote(@PathVariable id: UUID): ReleaseResponse = service.promote(id)

    @PostMapping("/{id}/rollback")
    fun rollback(@PathVariable id: UUID): ReleaseResponse = service.rollback(id)

    @PostMapping("/{id}/redeploy")
    fun redeploy(@PathVariable id: UUID, @RequestBody request: RedeployRequest): ReleaseResponse = service.redeploy(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)
}