// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import dev.juergenreiss.cdrm.release.ReleaseHistory
import dev.juergenreiss.cdrm.release.ReleaseHistoryAction
import dev.juergenreiss.cdrm.release.ReleaseHistoryRepository
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import dev.juergenreiss.cdrm.workload.WorkloadStage
import dev.juergenreiss.cdrm.workload.WorkloadStageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ProductDeploymentOverviewServiceTest {

    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var workloadRepository: WorkloadRepository

    @Mock
    private lateinit var workloadStageRepository: WorkloadStageRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var releaseHistoryRepository: ReleaseHistoryRepository

    @Mock
    private lateinit var rebac: RebacContext

    private lateinit var service: ProductDeploymentOverviewService

    @BeforeEach
    fun setUp() {
        service = ProductDeploymentOverviewService(
            productRepository, workloadRepository, workloadStageRepository, stageRepository, releaseHistoryRepository, rebac,
        )
    }

    private fun persistedProduct(
        id: UUID = UUID.randomUUID(),
        name: String = "Product",
        isGroup: Boolean = false,
        productGroupId: UUID? = null,
    ) = Product(
        id = id,
        name = name,
        description = null,
        isGroup = isGroup,
        productGroupId = productGroupId,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedWorkload(
        id: UUID = UUID.randomUUID(),
        name: String = "workload",
        productId: UUID,
        kubernetes: Boolean = true,
        kubernetesKind: KubernetesKind? = KubernetesKind.DEPLOYMENT,
        kubernetesNameSpace: String? = "platform",
    ) = Workload(
        id = id,
        name = name,
        productId = productId,
        description = null,
        kubernetes = kubernetes,
        kubernetesKind = kubernetesKind,
        kubernetesNameSpace = kubernetesNameSpace,
        pipeline = "pipeline",
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
        kubernetesContext: String? = "ctx",
        namespacePrefix: String? = null,
    ) = Stage(
        id = id,
        pipeline = pipeline,
        name = name,
        description = null,
        order = order,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext = kubernetesContext,
        namespacePrefix = namespacePrefix,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedHistoryEntry(
        releaseId: UUID = UUID.randomUUID(),
        workloadId: UUID?,
        productId: UUID = UUID.randomUUID(),
        workloadName: String = "workload",
        image: String = "registry.example.com/app:1.0.0",
        stageId: UUID,
        stageName: String = "stage",
        action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED,
        createdAt: Instant = Instant.now(),
    ) = ReleaseHistory(
        id = UUID.randomUUID(),
        releaseId = releaseId,
        workloadId = workloadId,
        productId = productId,
        productName = "product",
        workloadName = workloadName,
        image = image,
        stageId = stageId,
        stageName = stageName,
        action = action,
        createdAt = createdAt,
        createdBy = UUID.randomUUID(),
    )

    private fun link(workloadId: UUID, stageId: UUID) = WorkloadStage(workloadId = workloadId, stageId = stageId)

    @Test
    fun `throws NOT_FOUND for an unknown product`() {
        given(productRepository.findById(any())).willReturn(Optional.empty())

        assertThrows(ResponseStatusException::class.java) { service.get(UUID.randomUUID()) }
    }

    @Test
    fun `hides a product the caller can't see`() {
        val product = persistedProduct(name = "Secret")
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct("Secret")).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.get(product.id!!) }

        assertEquals(404, exception.statusCode.value())
    }

    @Test
    fun `returns an empty stage list when the product has no workloads`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(emptyList())

        val result = service.get(product.id!!)

        assertTrue(result.stages.isEmpty())
    }

    @Test
    fun `tabs are the union of stages across workloads spanning different pipelines`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val workloadA = persistedWorkload(productId = product.id!!, name = "a")
        val workloadB = persistedWorkload(productId = product.id!!, name = "b")
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(listOf(workloadA, workloadB))

        val stageFrankfurt = persistedStage(name = "frankfurt-qa", pipeline = "frankfurt", order = 1)
        val stageParis = persistedStage(name = "paris-qa", pipeline = "paris", order = 1)
        given(workloadStageRepository.findByWorkloadIdIn(listOf(workloadA.id!!, workloadB.id!!)))
            .willReturn(listOf(link(workloadA.id!!, stageFrankfurt.id!!), link(workloadB.id!!, stageParis.id!!)))
        given(stageRepository.findAllById(setOf(stageFrankfurt.id!!, stageParis.id!!))).willReturn(listOf(stageFrankfurt, stageParis))
        given(
            releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(
                listOf(workloadA.id!!, workloadB.id!!), setOf(stageFrankfurt.id!!, stageParis.id!!),
            )
        ).willReturn(emptyList())

        val result = service.get(product.id!!)

        assertEquals(setOf("frankfurt", "paris"), result.stages.map { it.pipeline }.toSet())
        assertEquals(listOf("a"), result.stages.single { it.pipeline == "frankfurt" }.workloads.map { it.workloadName })
        assertEquals(listOf("b"), result.stages.single { it.pipeline == "paris" }.workloads.map { it.workloadName })
    }

    @Test
    fun `a workload not linked to a stage does not appear in that stage's tab`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val workload = persistedWorkload(productId = product.id!!)
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(listOf(workload))

        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadIdIn(listOf(workload.id!!))).willReturn(emptyList())
        given(stageRepository.findAllById(emptySet<UUID>())).willReturn(emptyList())
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), emptySet())).willReturn(emptyList())

        val result = service.get(product.id!!)

        assertTrue(result.stages.isEmpty())
    }

    @Test
    fun `latestRelease is null when the workload has never been deployed at that stage`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val workload = persistedWorkload(productId = product.id!!)
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(listOf(workload))
        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadIdIn(listOf(workload.id!!))).willReturn(listOf(link(workload.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), setOf(stage.id!!))).willReturn(emptyList())

        val result = service.get(product.id!!)

        assertNull(result.stages.single().workloads.single().latestRelease)
    }

    @Test
    fun `latestRelease reflects the matching history row, mapped via the shared overview mapper`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val workload = persistedWorkload(productId = product.id!!)
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(listOf(workload))
        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadIdIn(listOf(workload.id!!))).willReturn(listOf(link(workload.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        val entry = persistedHistoryEntry(workloadId = workload.id, stageId = stage.id!!, action = ReleaseHistoryAction.ROLLED_BACK, image = "app:9.9")
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), setOf(stage.id!!))).willReturn(listOf(entry))

        val result = service.get(product.id!!)

        val latest = result.stages.single().workloads.single().latestRelease!!
        assertEquals(ReleaseHistoryAction.ROLLED_BACK, latest.action)
        assertEquals("app:9.9", latest.image)
        assertNull(latest.scheduledAt)
    }

    @Test
    fun `namespace is the stage prefix plus the workload's own kubernetesNameSpace, and null without one`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val withNamespace = persistedWorkload(productId = product.id!!, name = "with-ns", kubernetesNameSpace = "platform")
        val withoutNamespace = persistedWorkload(
            productId = product.id!!, name = "without-ns", kubernetes = false, kubernetesKind = null, kubernetesNameSpace = null,
        )
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(listOf(withNamespace, withoutNamespace))
        val stage = persistedStage(namespacePrefix = "prod-")
        given(workloadStageRepository.findByWorkloadIdIn(listOf(withNamespace.id!!, withoutNamespace.id!!))).willReturn(
            listOf(link(withNamespace.id!!, stage.id!!), link(withoutNamespace.id!!, stage.id!!))
        )
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        given(
            releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(withNamespace.id!!, withoutNamespace.id!!), setOf(stage.id!!))
        ).willReturn(emptyList())

        val result = service.get(product.id!!)

        val workloads = result.stages.single().workloads.associateBy { it.workloadName }
        assertEquals("prod-platform", workloads.getValue("with-ns").namespace)
        assertNull(workloads.getValue("without-ns").namespace)
    }

    @Test
    fun `a workload the caller can't see is excluded from every stage`() {
        val product = persistedProduct()
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct(product.name)).willReturn(true)

        val visible = persistedWorkload(productId = product.id!!, name = "visible")
        val hidden = persistedWorkload(productId = product.id!!, name = "hidden")
        given(rebac.canSeeWorkload(product.name, "visible")).willReturn(true)
        given(rebac.canSeeWorkload(product.name, "hidden")).willReturn(false)
        given(workloadRepository.findByProductIdIn(setOf(product.id!!))).willReturn(listOf(visible, hidden))

        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadIdIn(listOf(visible.id!!))).willReturn(listOf(link(visible.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(visible.id!!), setOf(stage.id!!))).willReturn(emptyList())

        val result = service.get(product.id!!)

        assertEquals(listOf("visible"), result.stages.single().workloads.map { it.workloadName })
    }

    @Test
    fun `a group's overview pools workloads from every descendant product and subgroup`() {
        val group = persistedProduct(name = "platform-group", isGroup = true)
        val childProduct = persistedProduct(name = "child", productGroupId = group.id)
        val childSubgroup = persistedProduct(name = "child-subgroup", isGroup = true, productGroupId = group.id)
        val grandchildProduct = persistedProduct(name = "grandchild", productGroupId = childSubgroup.id)

        given(productRepository.findById(group.id!!)).willReturn(Optional.of(group))
        given(rebac.canSeeProduct(anyString())).willReturn(true)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)
        given(productRepository.findByProductGroupIdIn(setOf(group.id!!))).willReturn(listOf(childProduct, childSubgroup))
        given(productRepository.findByProductGroupIdIn(setOf(childSubgroup.id!!))).willReturn(listOf(grandchildProduct))

        val workloadA = persistedWorkload(productId = childProduct.id!!, name = "a")
        val workloadB = persistedWorkload(productId = grandchildProduct.id!!, name = "b")
        given(
            workloadRepository.findByProductIdIn(setOf(childProduct.id!!, childSubgroup.id!!, grandchildProduct.id!!))
        ).willReturn(listOf(workloadA, workloadB))

        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadIdIn(listOf(workloadA.id!!, workloadB.id!!)))
            .willReturn(listOf(link(workloadA.id!!, stage.id!!), link(workloadB.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        given(
            releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workloadA.id!!, workloadB.id!!), setOf(stage.id!!))
        ).willReturn(emptyList())

        val result = service.get(group.id!!)

        assertTrue(result.isGroup)
        val workloads = result.stages.single().workloads.associateBy { it.workloadName }
        assertEquals("child", workloads.getValue("a").productName)
        assertEquals("grandchild", workloads.getValue("b").productName)
    }

    @Test
    fun `a descendant product the caller can't see is excluded from the group overview`() {
        val group = persistedProduct(name = "platform-group", isGroup = true)
        val visibleChild = persistedProduct(name = "visible-child", productGroupId = group.id)
        val hiddenChild = persistedProduct(name = "hidden-child", productGroupId = group.id)

        given(productRepository.findById(group.id!!)).willReturn(Optional.of(group))
        given(rebac.canSeeProduct(group.name)).willReturn(true)
        given(rebac.canSeeProduct(visibleChild.name)).willReturn(true)
        given(rebac.canSeeProduct(hiddenChild.name)).willReturn(false)
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)
        given(productRepository.findByProductGroupIdIn(setOf(group.id!!))).willReturn(listOf(visibleChild, hiddenChild))

        val workload = persistedWorkload(productId = visibleChild.id!!, name = "workload")
        given(workloadRepository.findByProductIdIn(setOf(visibleChild.id!!))).willReturn(listOf(workload))

        val stage = persistedStage()
        given(workloadStageRepository.findByWorkloadIdIn(listOf(workload.id!!))).willReturn(listOf(link(workload.id!!, stage.id!!)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))
        given(releaseHistoryRepository.findLatestByWorkloadIdInAndStageIdIn(listOf(workload.id!!), setOf(stage.id!!))).willReturn(emptyList())

        val result = service.get(group.id!!)

        assertEquals(listOf("workload"), result.stages.single().workloads.map { it.workloadName })
    }
}
