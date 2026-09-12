// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import dev.juergenreiss.cdrm.release.ReleaseHistoryRepository
import dev.juergenreiss.cdrm.release.toOverviewEntry
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.stage.effectiveNamespaceFor
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import dev.juergenreiss.cdrm.workload.WorkloadStageRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Backs the product detail view's per-stage tabs: for one product, every stage any of
// its workloads is linked to (a product's workloads can span multiple pipelines — there
// is no single "the" pipeline for a product), each with that stage's workloads and
// their latest deploy/rollback/redeploy there. Deliberately excludes any live
// Kubernetes read (see WorkloadService.liveStatus) — this only ever reads from
// Postgres, so it stays fast regardless of cluster reachability; the frontend fetches
// live status separately, per row, after this has already rendered.
@Service
@Transactional(readOnly = true)
class ProductDeploymentOverviewService(
    private val productRepository: ProductRepository,
    private val workloadRepository: WorkloadRepository,
    private val workloadStageRepository: WorkloadStageRepository,
    private val stageRepository: StageRepository,
    private val releaseHistoryRepository: ReleaseHistoryRepository,
    private val rebac: RebacContext,
) {
    fun get(productId: UUID): ProductDeploymentOverviewResponse {
        val product = productRepository.findById(productId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (!rebac.canSeeProduct(product.name)) throw ResponseStatusException(HttpStatus.NOT_FOUND)

        val workloads = workloadRepository.findByProductId(productId).filter { rebac.canSeeWorkload(product.name, it.name) }
        if (workloads.isEmpty()) return ProductDeploymentOverviewResponse(product.id!!, product.name, emptyList())

        val workloadIds = workloads.mapNotNull { it.id }
        val links = workloadStageRepository.findByWorkloadIdIn(workloadIds)
        val stageIds = links.map { it.stageId }.toSet()
        val stagesById = stageRepository.findAllById(stageIds).associateBy { it.id!! }
        val latestByWorkloadStage = releaseHistoryRepository
            .findLatestByWorkloadIdInAndStageIdIn(workloadIds, stageIds)
            .filter { it.workloadId != null }
            .associateBy { it.workloadId!! to it.stageId }
        val workloadIdsByStage = links.groupBy({ it.stageId }, { it.workloadId })
        val workloadsById = workloads.associateBy { it.id!! }

        val stages = stagesById.values.sortedWith(compareBy({ it.pipeline }, { it.order })).map { stage ->
            val stageWorkloads = (workloadIdsByStage[stage.id] ?: emptyList())
                .mapNotNull { workloadsById[it] }
                .sortedBy { it.name }
                .map { workload ->
                    ProductStageWorkloadOverview(
                        workloadId = workload.id!!,
                        workloadName = workload.name,
                        kubernetes = workload.kubernetes,
                        kubernetesKind = workload.kubernetesKind,
                        namespace = stage.effectiveNamespaceFor(workload),
                        latestRelease = latestByWorkloadStage[workload.id to stage.id]?.toOverviewEntry(stage.order),
                    )
                }
            ProductStageOverview(
                stageId = stage.id!!,
                stageName = stage.name,
                pipeline = stage.pipeline,
                order = stage.order,
                kubernetesContext = stage.kubernetesContext,
                deploymentPolicy = stage.deploymentPolicy,
                workloads = stageWorkloads,
            )
        }
        return ProductDeploymentOverviewResponse(product.id!!, product.name, stages)
    }
}
