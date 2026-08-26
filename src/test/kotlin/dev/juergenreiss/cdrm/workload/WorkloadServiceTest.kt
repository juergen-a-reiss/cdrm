package dev.juergenreiss.cdrm.workload

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
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
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
    private lateinit var currentUser: AuditorAware<UUID>

    private lateinit var service: WorkloadService

    @BeforeEach
    fun setUp() {
        service = WorkloadService(repository, stageRepository, workloadStageRepository, currentUser)
    }

    private fun persistedStage(order: Int, name: String = "Stage-$order") = Stage(
        id = UUID.randomUUID(),
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

        val result = service.create(WorkloadRequest(name = "Release 1", productId = saved.productId, description = null, kubernetes = false))

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
    fun `create with no existing stages links to nothing`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(stageRepository.findAll()).willReturn(emptyList())
        given(stageRepository.findAll(Sort.by("order"))).willReturn(emptyList())

        val saved = persistedWorkload()
        given(repository.save(any())).willReturn(saved)
        given(workloadStageRepository.findByWorkloadId(saved.id!!)).willReturn(emptyList())

        service.create(WorkloadRequest(name = saved.name, productId = saved.productId, description = null, kubernetes = false))

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<WorkloadStage>>
        verify(workloadStageRepository).saveAll(captor.capture())
        assertEquals(emptyList<WorkloadStage>(), captor.value)
    }

    @Test
    fun `create throws when current user cannot be resolved`() {
        given(currentUser.currentAuditor).willReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) {
            service.create(WorkloadRequest(name = "Release", productId = UUID.randomUUID(), description = null, kubernetes = false))
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
        given(stageRepository.findAllById(requestedIds.toSet())).willReturn(listOf(qa, prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa, prod))

        service.update(
            workloadId,
            WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, stageIds = requestedIds)
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
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(emptyList())
        given(stageRepository.findAll(Sort.by("order"))).willReturn(emptyList())

        service.update(
            workloadId,
            WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, stageIds = null)
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
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(
            listOf(WorkloadStage(workloadId = workloadId, stageId = dev.id!!))
        )
        given(stageRepository.findAllById(emptySet<UUID>())).willReturn(emptyList())
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        service.update(
            workloadId,
            WorkloadRequest(name = "Release 1", productId = existing.productId, description = null, kubernetes = false, stageIds = emptyList())
        )

        @Suppress("UNCHECKED_CAST")
        val removeCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<WorkloadStage>>
        verify(workloadStageRepository).deleteAll(removeCaptor.capture())
        assertEquals(listOf(dev.id), removeCaptor.value.map { it.stageId })
        verify(workloadStageRepository, never()).saveAll(any<List<WorkloadStage>>())
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
        given(stageRepository.findAllById(setOf(knownStage.id!!, unknownId))).willReturn(listOf(knownStage))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                workloadId,
                WorkloadRequest(
                    name = "Release 1",
                    productId = existing.productId,
                    description = null,
                    kubernetes = false,
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

    @Test
    fun `create rejects kubernetes true without a kind`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                WorkloadRequest(name = "Release", productId = UUID.randomUUID(), description = null, kubernetes = true, kubernetesKind = null)
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
        given(stageRepository.findAll()).willReturn(emptyList())
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
                    kubernetesNameSpace = null,
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
                    kubernetesNameSpace = "payments",
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }
}
