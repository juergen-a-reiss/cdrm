// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/workloads")
class WorkloadController(private val service: WorkloadService) {
    @GetMapping
    fun findAll(): List<WorkloadResponse> = service.findAll()

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): WorkloadResponse = service.findById(id)

    @Operation(description = "Creates a workload. stageIds in the request body is ignored — the new workload is always linked to every existing stage.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: WorkloadRequest): WorkloadResponse = service.create(request)

    @Operation(
        description = "Updates a workload. stageIds lets the frontend change which stages it's linked to: " +
            "omit/null leaves the current links unchanged, an empty array unlinks from all stages, and a " +
            "non-empty array replaces the links with exactly those stage ids. Unknown stage ids are rejected with 400."
    )
    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: WorkloadRequest): WorkloadResponse = service.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)
}