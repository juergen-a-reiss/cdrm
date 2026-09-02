package dev.juergenreiss.cdrm.workload

import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.AuditorAware
import org.springframework.data.domain.Sort
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WorkloadServiceTest {

    @Mock
    private lateinit var repository: WorkloadRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var workloadStageRepository: WorkloadStageRepository

    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var currentUser: AuditorAware<UUID>

    @Mock
    private lateinit var rebac: RebacContext

    private lateinit var service: WorkloadService

    @BeforeEach
    fun setUp() {
        service = WorkloadService(repository, stageRepository, workloadStageRepository, productRepository, currentUser, rebac)
    }

    private fun persistedStage(order: Int, name: String = "Stage-$order", pipeline: String = "pipeline") = Stage(
        id = UUID.randomUUID(),
        pipeline = pipeline,
        name = name,
        description = null,
        order = order,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedWorkload(
        id: UUID = UUID.randomUUID(),
        name: String = "Release",
        productId: UUID = UUID.randomUUID(),
    ) = Workload(
        id = id,
        name = name,
        productId = productId,
        description = null,
        kubernetes = false,
        pipeline = "pipeline",
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `create links the new workload to every existing stage`() {
        val userId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(userId))

        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        val prod = persistedStage(order = 3, name = "Prod")
        given(stageRepository.findAll()).willReturn(listOf(dev, qa, prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa, prod))

        val saved = persistedWorkload(name = "Release 1")
        given(repository.save(any())).willReturn(saved)
        given(workloadStageRepository.findByWorkloadId(saved.id!!)).willReturn(
            listOf(dev, qa, prod).map { WorkloadStage(workloadId = saved.id!!, stageId = it.id!!) }
        )

        val result = service.create(WorkloadRequest(name = "Release 1", productId = saved.productId, description = null, kubernetes = false, pipeline = "pipeline"))

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<WorkloadStage>>
        verify(workloadStageRepository).saveAll(captor.capture())
        val linked = captor.value

        assertEquals(setOf(dev.id, qa.id, prod.id), linked.map { it.stageId }.toSet())
        assertEquals(3, linked.size)
        assertEquals(setOf(saved.id), linked.map { it.workloadId }.toSet())

        assertEquals(listOf("Dev", "QA", "Prod"), result.stages.map { it.name })
    }

    @Test
    fun `create rejects when no stages exist at all`() {
        given(stageRepository.findAll()).willReturn(emptyList())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(WorkloadRequest(name = "Release", productId = UUID.randomUUID(), description = null, kubernetes = false, pipeline = "pipeline"))
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create rejects when stages exist but none belong to the requested pipeline`() {
        given(stageRepository.findAll()).willReturn(listOf(persistedStage(order = 1)))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(WorkloadRequest(name = "Release", productId = UUID.randomUUID(), description = null, kubernetes = false, pipeline = "other-pipeline"))
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create throws when current user cannot be resolved`() {
        given(stageRepository.findAll()).willReturn(listOf(persistedStage(order = 1)))
        given(currentUser.currentAuditor).willReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) {
            service.create(WorkloadRequest(name = "Release", productId = UUID.randomUUID(), description = null, kubernetes = false, pipeline = "pipeline"))
        }
    }

    @Test
    fun `update reconciles stage links to match the requested set`() {
        val workloadId = UUID.randomUUID()
        val existing = persistedWorkload(id = workloadId, name = "Release 1")
        given(repository.findById(workloadId)).willReturn(Optional.of(existing))
        given(repository.save(existing)).willReturn(existing)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        val prod = persistedStage(order = 3, name = "Prod")

        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(
            listOf(
                WorkloadStage(workloadId = workloadId, stageId = dev.id!!),
                WorkloadStage(workloadId = workloadId, stageId = qa.id!!),
            )
        )

        val requestedIds = listOf(qa.id!!, prod.id!!)
        given(stageRepository.findAll()).willReturn(listOf(dev, qa, prod))
        given(stageRepository.findAllById(requestedIds.toSet())).willReturn(listOf(qa, prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa, prod))

        service.update(
            workloadId,
            WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, pipeline = "pipeline", stageIds = requestedIds)
        )

        @Suppress("UNCHECKED_CAST")
        val removeCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<WorkloadStage>>
        verify(workloadStageRepository).deleteAll(removeCaptor.capture())
        assertEquals(listOf(dev.id), removeCaptor.value.map { it.stageId })

        @Suppress("UNCHECKED_CAST")
        val addCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<WorkloadStage>>
        verify(workloadStageRepository).saveAll(addCaptor.capture())
        assertEquals(listOf(prod.id), addCaptor.value.map { it.stageId })
    }

    @Test
    fun `update with null stageIds leaves stage links untouched`() {
        val workloadId = UUID.randomUUID()
        val existing = persistedWorkload(id = workloadId, name = "Release 1")
        given(repository.findById(workloadId)).willReturn(Optional.of(existing))
        given(repository.save(existing)).willReturn(existing)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(stageRepository.findAll()).willReturn(listOf(persistedStage(order = 1)))
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(emptyList())
        given(stageRepository.findAll(Sort.by("order"))).willReturn(emptyList())

        service.update(
            workloadId,
            WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, pipeline = "pipeline", stageIds = null)
        )

        verify(workloadStageRepository, never()).saveAll(any<List<WorkloadStage>>())
        verify(workloadStageRepository, never()).deleteAll(any<List<WorkloadStage>>())
    }

    @Test
    fun `update with empty stageIds unlinks all stages`() {
        val workloadId = UUID.randomUUID()
        val existing = persistedWorkload(id = workloadId, name = "Release 1")
        given(repository.findById(workloadId)).willReturn(Optional.of(existing))
        given(repository.save(existing)).willReturn(existing)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val dev = persistedStage(order = 1, name = "Dev")
        given(stageRepository.findAll()).willReturn(listOf(dev))
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(
            listOf(WorkloadStage(workloadId = workloadId, stageId = dev.id!!))
        )
        given(stageRepository.findAllById(emptySet<UUID>())).willReturn(emptyList())
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        service.update(
            workloadId,
            WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, pipeline = "pipeline", stageIds = emptyList())
        )

        @Suppress("UNCHECKED_CAST")
        val removeCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<WorkloadStage>>
        verify(workloadStageRepository).deleteAll(removeCaptor.capture())
        assertEquals(listOf(dev.id), removeCaptor.value.map { it.stageId })
        verify(workloadStageRepository, never()).saveAll(any<List<WorkloadStage>>())
    }

    @Test
    fun `update rejects when the requested pipeline has no stages`() {
        val workloadId = UUID.randomUUID()
        given(stageRepository.findAll()).willReturn(listOf(persistedStage(order = 1, pipeline = "other-pipeline")))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                workloadId,
                WorkloadRequest(name = "Release 1", productId = UUID.randomUUID(), description = null, kubernetes = false, pipeline = "pipeline"),
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `update rejects a requested stage that belongs to a different pipeline`() {
        val workloadId = UUID.randomUUID()
        val existing = persistedWorkload(id = workloadId, name = "Release 1")
        given(repository.findById(workloadId)).willReturn(Optional.of(existing))

        val matchingStage = persistedStage(order = 1, name = "Dev", pipeline = "pipeline")
        val otherPipelineStage = persistedStage(order = 1, name = "Other-Dev", pipeline = "other-pipeline")
        given(stageRepository.findAll()).willReturn(listOf(matchingStage, otherPipelineStage))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                workloadId,
                WorkloadRequest(
                    name = "Release 1",
                    productId = existing.productId,
                    description = null,
                    kubernetes = false,
                    pipeline = "pipeline",
                    stageIds = listOf(matchingStage.id!!, otherPipelineStage.id!!),
                ),
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
        verify(workloadStageRepository, never()).saveAll(any<List<WorkloadStage>>())
        verify(workloadStageRepository, never()).deleteAll(any<List<WorkloadStage>>())
    }

    @Test
    fun `update rejects changing pipeline while a stage link from the old pipeline stays untouched`() {
        val workloadId = UUID.randomUUID()
        val existing = persistedWorkload(id = workloadId, name = "Release 1")
        given(repository.findById(workloadId)).willReturn(Optional.of(existing))

        val newPipelineStage = persistedStage(order = 1, name = "Dev", pipeline = "new-pipeline")
        val oldPipelineStage = persistedStage(order = 1, name = "Old-Dev", pipeline = "old-pipeline")
        given(stageRepository.findAll()).willReturn(listOf(newPipelineStage, oldPipelineStage))
        // stageIds is omitted (null) — links stay as they are, still pointing at the old
        // pipeline's stage, which is now inconsistent with the workload's new pipeline.
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(
            listOf(WorkloadStage(workloadId = workloadId, stageId = oldPipelineStage.id!!))
        )

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                workloadId,
                WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, pipeline = "new-pipeline"),
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `update rejects unknown stage ids without touching links`() {
        val workloadId = UUID.randomUUID()
        val existing = persistedWorkload(id = workloadId, name = "Release 1")
        given(repository.findById(workloadId)).willReturn(Optional.of(existing))
        given(repository.save(existing)).willReturn(existing)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val knownStage = persistedStage(order = 1, name = "Dev")
        val unknownId = UUID.randomUUID()
        given(stageRepository.findAll()).willReturn(listOf(knownStage))
        given(stageRepository.findAllById(setOf(knownStage.id!!, unknownId))).willReturn(listOf(knownStage))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                workloadId,
                WorkloadRequest(
                    name = "Release 1",
                    productId = existing.productId,
                    description = null,
                    kubernetes = false,
                    pipeline = "pipeline",
                    stageIds = listOf(knownStage.id!!, unknownId),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(workloadStageRepository, never()).saveAll(any<List<WorkloadStage>>())
        verify(workloadStageRepository, never()).deleteAll(any<List<WorkloadStage>>())
    }

    @Test
    fun `findById throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.findById(id)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) { service.findById(id) }

        assertEquals(404, exception.statusCode.value())
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

    @Test
    fun `findAll returns only workloads the caller's ReBAC claims allow`() {
        val product = persistedProduct(name = "Platform")
        val visible = persistedWorkload(name = "platform-api", productId = product.id!!)
        val hidden = persistedWorkload(name = "platform-worker", productId = product.id!!)
        given(repository.findAll()).willReturn(listOf(visible, hidden))
        given(productRepository.findAllById(setOf(product.id!!))).willReturn(listOf(product))
        given(workloadStageRepository.findByWorkloadId(visible.id!!)).willReturn(emptyList())
        given(rebac.canSeeWorkload("Platform", "platform-api")).willReturn(true)
        given(rebac.canSeeWorkload("Platform", "platform-worker")).willReturn(false)

        val result = service.findAll()

        assertEquals(listOf("platform-api"), result.map { it.name })
    }

    @Test
    fun `findAll sorts by name by default`() {
        val product = persistedProduct(name = "Platform")
        val a = persistedWorkload(name = "a-workload", productId = product.id!!)
        val b = persistedWorkload(name = "b-workload", productId = product.id!!)
        given(repository.findAll()).willReturn(listOf(b, a))
        given(productRepository.findAllById(setOf(product.id!!))).willReturn(listOf(product))
        given(workloadStageRepository.findByWorkloadId(a.id!!)).willReturn(emptyList())
        given(workloadStageRepository.findByWorkloadId(b.id!!)).willReturn(emptyList())
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val result = service.findAll()

        assertEquals(listOf("a-workload", "b-workload"), result.map { it.name })
    }

    @Test
    fun `findAll sorts by productName`() {
        val productA = persistedProduct(name = "a-product")
        val productB = persistedProduct(name = "b-product")
        val workloadInB = persistedWorkload(name = "workload-1", productId = productB.id!!)
        val workloadInA = persistedWorkload(name = "workload-2", productId = productA.id!!)
        given(repository.findAll()).willReturn(listOf(workloadInB, workloadInA))
        given(productRepository.findAllById(setOf(productB.id!!, productA.id!!))).willReturn(listOf(productA, productB))
        given(workloadStageRepository.findByWorkloadId(workloadInA.id!!)).willReturn(emptyList())
        given(workloadStageRepository.findByWorkloadId(workloadInB.id!!)).willReturn(emptyList())
        given(rebac.canSeeWorkload(anyString(), anyString())).willReturn(true)

        val result = service.findAll("productName,asc")

        assertEquals(listOf("workload-2", "workload-1"), result.map { it.name })
    }

    @Test
    fun `findAll rejects an unknown sort key`() {
        given(repository.findAll()).willReturn(emptyList())

        val exception = assertThrows(ResponseStatusException::class.java) { service.findAll("bogus,asc") }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `findById throws 404 for a workload the caller's ReBAC claims hide`() {
        val product = persistedProduct(name = "Payments")
        val workload = persistedWorkload(name = "payments-gateway", productId = product.id!!)
        given(repository.findById(workload.id!!)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeWorkload("Payments", "payments-gateway")).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.findById(workload.id!!) }

        assertEquals(404, exception.statusCode.value())
    }

    @Test
    fun `delete removes existing workload after resolving current user`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        service.delete(id)

        verify(repository).deleteById(id)
    }

    @Test
    fun `delete throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.delete(id) }

        assertEquals(404, exception.statusCode.value())
        verify(repository, never()).deleteById(id)
    }

    @Test
    fun `delete throws 409 when workload is still referenced by a release`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(repository.flush()).willThrow(DataIntegrityViolationException::class.java)

        val exception = assertThrows(ResponseStatusException::class.java) { service.delete(id) }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `create rejects kubernetes true without a kind`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                WorkloadRequest(name = "Release", productId = UUID.randomUUID(), description = null, kubernetes = true, kubernetesKind = null, pipeline = "--")
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create rejects kubernetes false with a kind set`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                WorkloadRequest(
                    name = "Release",
                    productId = UUID.randomUUID(),
                    description = null,
                    kubernetes = false,
                    kubernetesKind = KubernetesKind.DEPLOYMENT,
                    pipeline = "--",
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create accepts kubernetes true with a kind and namespace set`() {
        val userId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(userId))
        given(stageRepository.findAll()).willReturn(listOf(persistedStage(order = 1)))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(emptyList())

        val saved = persistedWorkload(name = "Release")
        given(repository.save(any())).willReturn(saved)
        given(workloadStageRepository.findByWorkloadId(saved.id!!)).willReturn(emptyList())

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Workload::class.java) as ArgumentCaptor<Workload>

        service.create(
            WorkloadRequest(
                name = "Release",
                productId = saved.productId,
                description = null,
                kubernetes = true,
                kubernetesKind = KubernetesKind.STATEFUL_SET,
                kubernetesNameSpace = "payments",
                pipeline = "pipeline",
            )
        )

        verify(repository).save(captor.capture())
        assertEquals(true, captor.value.kubernetes)
        assertEquals(KubernetesKind.STATEFUL_SET, captor.value.kubernetesKind)
        assertEquals("payments", captor.value.kubernetesNameSpace)
    }

    @Test
    fun `create rejects kubernetes true without a namespace`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                WorkloadRequest(
                    name = "Release",
                    productId = UUID.randomUUID(),
                    description = null,
                    kubernetes = true,
                    kubernetesKind = KubernetesKind.DEPLOYMENT,
                    kubernetesNameSpace = null, pipeline = "--",
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create rejects kubernetes false with a namespace set`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                WorkloadRequest(
                    name = "Release",
                    productId = UUID.randomUUID(),
                    description = null,
                    kubernetes = false,
                    kubernetesNameSpace = "payments", pipeline = "--",
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }
}
