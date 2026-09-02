package dev.juergenreiss.cdrm.stage

import dev.juergenreiss.cdrm.cluster.Cluster
import dev.juergenreiss.cdrm.cluster.ClusterRepository
import dev.juergenreiss.cdrm.cluster.ClusterType
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.AuditorAware
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class StageServiceTest {

    @Mock
    private lateinit var repository: StageRepository

    @Mock
    private lateinit var clusterRepository: ClusterRepository

    @Mock
    private lateinit var stageClusterRepository: StageClusterRepository

    @Mock
    private lateinit var currentUser: AuditorAware<UUID>

    private lateinit var service: StageService

    @BeforeEach
    fun setUp() {
        service = StageService(repository, clusterRepository, stageClusterRepository, currentUser)
    }

    private fun persistedStage(
        id: UUID = UUID.randomUUID(),
        pipeline: String = "pipeline",
        name: String = "Draft",
        description: String? = "desc",
        order: Int = 1,
        deploymentPolicy: DeploymentPolicy = DeploymentPolicy.IMMEDIATE,
        createdBy: UUID = UUID.randomUUID(),
        modifiedBy: UUID = createdBy,
    ) = Stage(
        id = id,
        pipeline = pipeline,
        name = name,
        description = description,
        order = order,
        deploymentPolicy = deploymentPolicy,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = createdBy,
        modifiedBy = modifiedBy,
    )

    private fun persistedCluster(name: String = "Cluster") = Cluster(
        id = UUID.randomUUID(),
        name = name,
        description = null,
        clusterType = ClusterType.K8S,
        url = URI("https://$name.example.com").toURL(),
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `findAll maps stages sorted by pipeline by default`() {
        val a = persistedStage(pipeline = "a-pipeline")
        val b = persistedStage(pipeline = "b-pipeline")
        given(repository.findAll()).willReturn(listOf(b, a))

        val result = service.findAll()

        assertEquals(listOf("a-pipeline", "b-pipeline"), result.map { it.pipeline })
    }

    @Test
    fun `findAll honors an explicit sort param`() {
        val dev = persistedStage(order = 1)
        val qa = persistedStage(order = 2)
        given(repository.findAll()).willReturn(listOf(dev, qa))

        val result = service.findAll("order,desc")

        assertEquals(listOf(2, 1), result.map { it.order })
    }

    @Test
    fun `findAll rejects an unknown sort key`() {
        given(repository.findAll()).willReturn(listOf(persistedStage()))

        val exception = assertThrows(ResponseStatusException::class.java) { service.findAll("clusters,asc") }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `findById returns mapped stage when present`() {
        val stage = persistedStage()
        given(repository.findById(stage.id!!)).willReturn(Optional.of(stage))

        val result = service.findById(stage.id!!)

        assertEquals(stage.id, result.id)
        assertEquals(stage.name, result.name)
    }

    @Test
    fun `findById throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.findById(id)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) { service.findById(id) }

        assertEquals(404, exception.statusCode.value())
    }

    @Test
    fun `create resolves current user as createdBy and modifiedBy`() {
        val userId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(userId))
        val captor = ArgumentCaptor.forClass(Stage::class.java)
        val saved = persistedStage(createdBy = userId, modifiedBy = userId)
        given(repository.save(captor.capture())).willReturn(saved)

        val result = service.create(StageRequest(pipeline = "pipeline", name = "Draft", description = "desc", order = 1, deploymentPolicy = DeploymentPolicy.IMMEDIATE))

        assertEquals("Draft", captor.value.name)
        assertEquals(userId, captor.value.createdBy)
        assertEquals(userId, captor.value.modifiedBy)
        assertEquals(saved.id, result.id)
        assertEquals(userId, result.createdBy)
    }

    @Test
    fun `create throws when current user cannot be resolved`() {
        given(currentUser.currentAuditor).willReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) {
            service.create(StageRequest(pipeline = "pipeline", name = "Draft", description = null, order = 1, deploymentPolicy = DeploymentPolicy.IMMEDIATE))
        }

        verify(repository, never()).save(any())
    }

    @Test
    fun `update overwrites fields and sets modifiedBy from current user`() {
        val originalUser = UUID.randomUUID()
        val stage = persistedStage(createdBy = originalUser, modifiedBy = originalUser)
        given(repository.findById(stage.id!!)).willReturn(Optional.of(stage))
        val newUser = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(newUser))
        given(repository.save(stage)).willReturn(stage)

        val result = service.update(stage.id!!, StageRequest(pipeline = "pipeline", name = "Renamed", description = "new desc", order = 2, deploymentPolicy = DeploymentPolicy.IMMEDIATE))

        assertEquals("Renamed", stage.name)
        assertEquals("new desc", stage.description)
        assertEquals(2, stage.order)
        assertEquals(originalUser, stage.createdBy)
        assertEquals(newUser, stage.modifiedBy)
        assertEquals(newUser, result.modifiedBy)
    }

    @Test
    fun `update throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.findById(id)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(id, StageRequest(pipeline = "pipeline", name = "Renamed", description = null, order = 2, deploymentPolicy = DeploymentPolicy.IMMEDIATE))
        }

        assertEquals(404, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `update reconciles cluster links to match the requested set`() {
        val stageId = UUID.randomUUID()
        val stage = persistedStage(id = stageId)
        given(repository.findById(stageId)).willReturn(Optional.of(stage))
        given(repository.save(stage)).willReturn(stage)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val keep = persistedCluster("keep")
        val add = persistedCluster("add")
        val remove = persistedCluster("remove")

        given(stageClusterRepository.findByStageId(stageId)).willReturn(
            listOf(
                StageCluster(stageId = stageId, clusterId = keep.id!!),
                StageCluster(stageId = stageId, clusterId = remove.id!!),
            )
        )

        val requestedIds = listOf(keep.id!!, add.id!!)
        given(clusterRepository.findAllById(requestedIds.toSet())).willReturn(listOf(keep, add))

        service.update(
            stageId,
            StageRequest(
                pipeline = "pipeline",
                name = "Draft",
                description = null,
                order = 1,
                deploymentPolicy = DeploymentPolicy.IMMEDIATE,
                clusterIds = requestedIds,
            )
        )

        @Suppress("UNCHECKED_CAST")
        val removeCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<StageCluster>>
        verify(stageClusterRepository).deleteAll(removeCaptor.capture())
        assertEquals(listOf(remove.id), removeCaptor.value.map { it.clusterId })

        @Suppress("UNCHECKED_CAST")
        val addCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<StageCluster>>
        verify(stageClusterRepository).saveAll(addCaptor.capture())
        assertEquals(listOf(add.id), addCaptor.value.map { it.clusterId })
    }

    @Test
    fun `update with null clusterIds leaves cluster links untouched`() {
        val stageId = UUID.randomUUID()
        val stage = persistedStage(id = stageId)
        given(repository.findById(stageId)).willReturn(Optional.of(stage))
        given(repository.save(stage)).willReturn(stage)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        service.update(
            stageId,
            StageRequest(pipeline = "pipeline", name = "Draft", description = null, order = 1, deploymentPolicy = DeploymentPolicy.IMMEDIATE, clusterIds = null)
        )

        verify(stageClusterRepository, never()).saveAll(any<List<StageCluster>>())
        verify(stageClusterRepository, never()).deleteAll(any<List<StageCluster>>())
    }

    @Test
    fun `update rejects unknown cluster ids without touching links`() {
        val stageId = UUID.randomUUID()
        val stage = persistedStage(id = stageId)
        given(repository.findById(stageId)).willReturn(Optional.of(stage))
        given(repository.save(stage)).willReturn(stage)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val known = persistedCluster("known")
        val unknownId = UUID.randomUUID()
        given(clusterRepository.findAllById(setOf(known.id!!, unknownId))).willReturn(listOf(known))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                stageId,
                StageRequest(
                    pipeline = "pipeline",
                    name = "Draft",
                    description = null,
                    order = 1,
                    deploymentPolicy = DeploymentPolicy.IMMEDIATE,
                    clusterIds = listOf(known.id!!, unknownId),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(stageClusterRepository, never()).saveAll(any<List<StageCluster>>())
        verify(stageClusterRepository, never()).deleteAll(any<List<StageCluster>>())
    }

    @Test
    fun `delete removes existing stage after resolving current user`() {
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
    fun `delete throws 409 when stage is still referenced by a release`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(repository.flush()).willThrow(DataIntegrityViolationException::class.java)

        val exception = assertThrows(ResponseStatusException::class.java) { service.delete(id) }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `delete does not remove stage when current user cannot be resolved`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) { service.delete(id) }

        verify(repository, never()).deleteById(id)
    }
}
