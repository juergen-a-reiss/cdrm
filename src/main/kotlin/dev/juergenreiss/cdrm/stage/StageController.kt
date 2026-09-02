// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/stages")
class StageController(private val service: StageService) {

    @GetMapping
    fun findAll(@RequestParam(required = false) sort: String?): List<StageResponse> = service.findAll(sort)

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): StageResponse = service.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: StageRequest): StageResponse = service.create(request)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: StageRequest): StageResponse = service.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)
}
