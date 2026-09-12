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

        // A group has no workloads of its own (see ProductService's group invariants) —
        // its overview pools every descendant product's (and subgroup's) workloads instead.
        val targetProducts = (if (product.isGroup) descendantProducts(product.id!!) else listOf(product))
            .filter { rebac.canSeeProduct(it.name) }
        val productsById = targetProducts.associateBy { it.id!! }
        if (productsById.isEmpty()) return ProductDeploymentOverviewResponse(product.id!!, product.name, product.isGroup, emptyList())

        val workloads = workloadRepository.findByProductIdIn(productsById.keys)
            .filter { workload -> productsById[workload.productId]?.let { rebac.canSeeWorkload(it.name, workload.name) } ?: false }
        if (workloads.isEmpty()) return ProductDeploymentOverviewResponse(product.id!!, product.name, product.isGroup, emptyList())

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
                        productId = workload.productId,
                        productName = productsById.getValue(workload.productId).name,
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
        return ProductDeploymentOverviewResponse(product.id!!, product.name, product.isGroup, stages)
    }

    // Breadth-first walk of the group tree rooted at groupId, one query per depth level.
    // Guards against a stray cycle in legacy data even though ProductService rejects new
    // ones (see its validateGroupInvariants) — same precaution ProductTree.vue takes.
    private fun descendantProducts(groupId: UUID): List<Product> {
        val result = mutableListOf<Product>()
        val visitedGroupIds = mutableSetOf(groupId)
        var frontier = setOf(groupId)
        while (frontier.isNotEmpty()) {
            val children = productRepository.findByProductGroupIdIn(frontier)
            result += children
            frontier = children.filter { it.isGroup && visitedGroupIds.add(it.id!!) }.map { it.id!! }.toSet()
        }
        return result
    }
}
