// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.release.ReleaseHistoryRepository
import dev.juergenreiss.cdrm.release.toOverviewEntry
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.stage.effectiveNamespaceFor
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Backs the workload detail view: for one workload, every stage it's linked to, each
// with that stage's latest deploy/rollback/redeploy — the same per-stage row shape
// ProductDeploymentOverviewService uses per-workload, just scoped to a single workload
// instead. Never touches Kubernetes (see WorkloadService.liveStatus for that).
@Service
@Transactional(readOnly = true)
class WorkloadDeploymentOverviewService(
    private val workloadRepository: WorkloadRepository,
    private val productRepository: ProductRepository,
    private val workloadStageRepository: WorkloadStageRepository,
    private val stageRepository: StageRepository,
    private val releaseHistoryRepository: ReleaseHistoryRepository,
    private val rebac: RebacContext,
) {
    fun get(workloadId: UUID): WorkloadDeploymentOverviewResponse {
        val workload = workloadRepository.findById(workloadId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val product = productRepository.findById(workload.productId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (!rebac.canSeeWorkload(product.name, workload.name)) throw ResponseStatusException(HttpStatus.NOT_FOUND)

        val links = workloadStageRepository.findByWorkloadId(workloadId)
        val stageIds = links.map { it.stageId }.toSet()
        val stagesById = stageRepository.findAllById(stageIds).associateBy { it.id!! }
        val latestByStage = releaseHistoryRepository
            .findLatestByWorkloadIdInAndStageIdIn(listOf(workloadId), stageIds)
            .filter { it.workloadId != null }
            .associateBy { it.stageId }

        val stages = stagesById.values.sortedBy { it.order }.map { stage ->
            WorkloadStageDeploymentOverview(
                stageId = stage.id!!,
                stageName = stage.name,
                order = stage.order,
                namespace = stage.effectiveNamespaceFor(workload),
                latestRelease = latestByStage[stage.id]?.toOverviewEntry(stage.order),
            )
        }
        return WorkloadDeploymentOverviewResponse(
            workloadId = workload.id!!,
            workloadName = workload.name,
            pipeline = workload.pipeline,
            productId = product.id!!,
            productName = product.name,
            kubernetes = workload.kubernetes,
            stages = stages,
        )
    }
}
