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
    fun findAll(@RequestParam(required = false) sort: String?): List<ReleaseResponse> = service.findAll(sort)

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): ReleaseResponse = service.findById(id)

    @GetMapping("/{id}/history")
    fun history(@PathVariable id: UUID): List<ReleaseHistoryEntry> = service.history(id)

    @GetMapping("/history")
    fun historyOverview(
        @RequestParam(required = false) sort: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) productId: List<UUID>?,
        @RequestParam(required = false) workloadId: List<UUID>?,
        @RequestParam(required = false) stageId: List<UUID>?,
        @RequestParam(required = false) pipeline: List<String>?,
        @RequestParam(required = false) action: List<ReleaseHistoryAction>?,
        @RequestParam(required = false) monthsBack: Int?,
        @RequestParam(required = false) search: String?,
    ): ReleaseHistoryPageResponse =
        service.historyOverview(sort, page, size, productId, workloadId, stageId, pipeline, action, monthsBack, search)

    @GetMapping("/history/summary")
    fun historySummary(
        @RequestParam groupBy: ReleaseHistoryGroupBy,
        @RequestParam(required = false) productId: List<UUID>?,
        @RequestParam(required = false) workloadId: List<UUID>?,
        @RequestParam(required = false) stageId: List<UUID>?,
        @RequestParam(required = false) pipeline: List<String>?,
        @RequestParam(required = false) action: List<ReleaseHistoryAction>?,
        @RequestParam(required = false) monthsBack: Int?,
    ): List<ReleaseHistorySummaryEntry> =
        service.historySummary(groupBy, productId, workloadId, stageId, pipeline, action, monthsBack)

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