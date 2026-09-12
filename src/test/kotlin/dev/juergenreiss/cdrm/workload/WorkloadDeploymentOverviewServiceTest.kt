// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.release.ReleaseHistory
import dev.juergenreiss.cdrm.release.ReleaseHistoryAction
import dev.juergenreiss.cdrm.release.ReleaseHistoryRepository
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WorkloadDeploymentOverviewServiceTest {

    @Mock
    private lateinit var workloadRepository: WorkloadRepository

    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var workloadStageRepository: WorkloadStageRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var releaseHistoryRepository: ReleaseHistoryRepository

    @Mock
    private lateinit var rebac: RebacContext

    private lateinit var service: WorkloadDeploymentOverviewService

    @BeforeEach
    fun setUp() {
        service = WorkloadDeploymentOverviewService(
            workloadRepository, productRepository, workloadStageRepository, stageRepository, releaseHistoryRepository, rebac,
        )
    }

    private fun persistedProduct(id: UUID = UUID.randomUUID(), name: String = "Product") = Product(
        id = id,
        name = name,
        description = null,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedWorkload(
        id: UUID = UUID.randomUUID(),
        name: String = "workload",
        productId: UUID,
        pipeline: String = "pipeline",
        kubernetesNameSpace: String? = "platform",
    ) = Workload(
        id = id,
        name = name,
        productId = productId,
        description = null,
        kubernetes = true,
        kubernetesKind = KubernetesKind.DEPLOYMENT,
        kubernetesNameSpace = kubernetesNameSpace,
        pipeline = pipeline,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedStage(
        id: UUID = UUID.randomUUID(),
        name: String = "stage",
        pipeline: String = "pipeline",
        order: Int = 1,
        namespacePrefix: String? = null,
    ) = Stage(
        id = id,
        pipeline = pipeline,
        name = name,
        description = null,
        order = order,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext = "ctx",
        namespacePrefix = namespacePrefix,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedHistoryEntry(
        workloadId: UUID?,
        stageId: UUID,
        image: String = "registry.example.com/app:1.0.0",
        action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED,
    ) = ReleaseHistory(
        id = UUID.randomUUID(),
        releaseId = UUID.randomUUID(),
        workloadId = workloadId,
        productId = UUID.randomUUID(),
        productName = "product",
        workloadName = "workload",
        image = image,
        stageId = stageId,
        stageName = "stage",
        action = action,
        createdAt = Instant.now(),
        createdBy = UUID.randomUUID(),
    )

    private fun link(workloadId: UUID, stageId: UUID) = WorkloadStage(workloadId = workloadId, stageId = stageId)

    @Test
    fun `throws NOT_FOUND for an unknown workload`() {
        given(workloadRepository.findById(any())).willReturn(Optional.empty())

        assertThrows(ResponseStatusException::class.java) { service.get(UUID.randomUUID()) }
    }

    @Test
    fun `hides a workload the caller can't see`() {
        val product = persistedProduct()
        val workload = persistedWorkload(productId = product.id!!, name = "secret")
        given(workloadRepository.findById(workload.id!!)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeWorkload(product.name, "secret")).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.get(workload.id!!) }

        assertEquals(404, exception.statusCode.value())
    }

    @Test
    fun `rows are every linked stage, sorted by order, with productId-productName and pipeline carried through`() {
        val product = persistedProduct(name = "platform")
        val workload = persistedWorkload(productId = product.id!!, pipeline = "frankfurt")
        given(workloadRepository.findById(workload.id!!)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeWorkload(product.name, workload.name)).willReturn(true)

        val stageQa = persistedStage(name = "qa", order = 1)
        val stageProd = persistedStage(name = "prod", order = 2)
        given(workloadStageRepository.findByWorkloadId(workload.id!!))
            .willReturn(listOf(link(workload.id!!, stageProd.id!!), link(workload.id!!, stageQa.id!!)))
        given(stageRepository.findAllById(setOf(stageProd.id!!, stageQa.id!!))).willReturn(listOf(stageProd, stageQa))
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), setOf(stageProd.id!!, stageQa.id!!)))
            .willReturn(emptyList())

        val result = service.get(workload.id!!)

        assertEquals(product.id, result.productId)
        assertEquals("platform", result.productName)
        assertEquals("frankfurt", result.pipeline)
        assertEquals(listOf("qa", "prod"), result.stages.map { it.stageName })
        assertTrue(result.stages.all { it.latestRelease == null })
    }

    @Test
    fun `latestRelease reflects the matching history row for that stage`() {
        val product = persistedProduct()
        val workload = persistedWorkload(productId = product.id!!)
        given(workloadRepository.findById(workload.id!!)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeWorkload(product.name, workload.name)).willReturn(true)

        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadId(workload.id!!)).willReturn(listOf(link(workload.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        val entry = persistedHistoryEntry(workloadId = workload.id, stageId = stage.id!!, action = ReleaseHistoryAction.ROLLED_BACK, image = "app:9.9")
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), setOf(stage.id!!))).willReturn(listOf(entry))

        val result = service.get(workload.id!!)

        val latest = result.stages.single().latestRelease!!
        assertEquals(ReleaseHistoryAction.ROLLED_BACK, latest.action)
        assertEquals("app:9.9", latest.image)
    }

    @Test
    fun `namespace is the stage prefix plus the workload's own kubernetesNameSpace, and null without one`() {
        val product = persistedProduct()
        val workload = persistedWorkload(productId = product.id!!, kubernetesNameSpace = null)
        given(workloadRepository.findById(workload.id!!)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeWorkload(product.name, workload.name)).willReturn(true)

        val stage = persistedStage(namespacePrefix = "prod-")
        given(workloadStageRepository.findByWorkloadId(workload.id!!)).willReturn(listOf(link(workload.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), setOf(stage.id!!))).willReturn(emptyList())

        val result = service.get(workload.id!!)

        assertNull(result.stages.single().namespace)
    }
}
