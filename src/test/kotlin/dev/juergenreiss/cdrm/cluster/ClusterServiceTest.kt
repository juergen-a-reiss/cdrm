package dev.juergenreiss.cdrm.cluster

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
class ClusterServiceTest {

    @Mock
    private lateinit var repository: ClusterRepository

    @Mock
    private lateinit var currentUser: AuditorAware<UUID>

    private lateinit var service: ClusterService

    @BeforeEach
    fun setUp() {
        service = ClusterService(repository, currentUser)
    }

    private fun persistedCluster(
        id: UUID = UUID.randomUUID(),
        name: String = "prod",
        clusterType: ClusterType = ClusterType.K8S,
        createdBy: UUID = UUID.randomUUID(),
        modifiedBy: UUID = createdBy,
    ) = Cluster(
        id = id,
        name = name,
        description = "desc",
        clusterType = clusterType,
        url = URI("https://$name.example.com").toURL(),
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = createdBy,
        modifiedBy = modifiedBy,
    )

    @Test
    fun `findAll maps clusters sorted by name by default`() {
        val a = persistedCluster().apply { name = "a-cluster" }
        val b = persistedCluster().apply { name = "b-cluster" }
        given(repository.findAll()).willReturn(listOf(b, a))

        val result = service.findAll()

        assertEquals(listOf("a-cluster", "b-cluster"), result.map { it.name })
    }

    @Test
    fun `findAll honors an explicit sort param`() {
        val a = persistedCluster().apply { name = "a-cluster" }
        val b = persistedCluster().apply { name = "b-cluster" }
        given(repository.findAll()).willReturn(listOf(a, b))

        val result = service.findAll("name,desc")

        assertEquals(listOf("b-cluster", "a-cluster"), result.map { it.name })
    }

    @Test
    fun `findAll rejects an unknown sort key`() {
        given(repository.findAll()).willReturn(listOf(persistedCluster()))

        val exception = assertThrows(ResponseStatusException::class.java) { service.findAll("bogus,asc") }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `findById returns mapped cluster when present`() {
        val cluster = persistedCluster()
        given(repository.findById(cluster.id!!)).willReturn(Optional.of(cluster))

        val result = service.findById(cluster.id!!)

        assertEquals(cluster.id, result.id)
        assertEquals(cluster.name, result.name)
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
        val captor = ArgumentCaptor.forClass(Cluster::class.java)
        val saved = persistedCluster(createdBy = userId, modifiedBy = userId)
        given(repository.save(captor.capture())).willReturn(saved)

        val result = service.create(
            ClusterRequests(name = "prod", description = "desc", clusterType = ClusterType.K8S, url = saved.url)
        )

        assertEquals("prod", captor.value.name)
        assertEquals(userId, captor.value.createdBy)
        assertEquals(saved.id, result.id)
    }

    @Test
    fun `create throws when current user cannot be resolved`() {
        given(currentUser.currentAuditor).willReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) {
            service.create(
                ClusterRequests(name = "prod", description = null, clusterType = ClusterType.K8S, url = URI("https://prod.example.com").toURL())
            )
        }

        verify(repository, never()).save(any())
    }

    @Test
    fun `update overwrites fields and sets modifiedBy from current user`() {
        val originalUser = UUID.randomUUID()
        val cluster = persistedCluster(createdBy = originalUser, modifiedBy = originalUser)
        given(repository.findById(cluster.id!!)).willReturn(Optional.of(cluster))
        val newUser = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(newUser))
        given(repository.save(cluster)).willReturn(cluster)

        val newUrl = URI("https://renamed.example.com").toURL()
        val result = service.update(
            cluster.id!!,
            ClusterRequests(name = "renamed", description = "new desc", clusterType = ClusterType.PROXMOX, url = newUrl)
        )

        assertEquals("renamed", cluster.name)
        assertEquals("new desc", cluster.description)
        assertEquals(ClusterType.PROXMOX, cluster.clusterType)
        assertEquals(newUser, cluster.modifiedBy)
        assertEquals(newUser, result.modifiedBy)
    }

    @Test
    fun `update throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.findById(id)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(id, ClusterRequests(name = "renamed", description = null, clusterType = ClusterType.K8S, url = URI("https://x.example.com").toURL()))
        }

        assertEquals(404, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `delete removes existing cluster after resolving current user`() {
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
    fun `delete throws 409 when cluster is still linked to a stage`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(repository.flush()).willThrow(DataIntegrityViolationException::class.java)

        val exception = assertThrows(ResponseStatusException::class.java) { service.delete(id) }

        assertEquals(409, exception.statusCode.value())
    }
}
