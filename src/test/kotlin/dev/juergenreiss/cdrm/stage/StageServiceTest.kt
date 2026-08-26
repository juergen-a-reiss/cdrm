package dev.juergenreiss.cdrm.stage

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
import org.springframework.data.domain.Sort
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class StageServiceTest {

    @Mock
    private lateinit var repository: StageRepository

    @Mock
    private lateinit var currentUser: AuditorAware<UUID>

    private lateinit var service: StageService

    @BeforeEach
    fun setUp() {
        service = StageService(repository, currentUser)
    }

    private fun persistedStage(
        id: UUID = UUID.randomUUID(),
        name: String = "Draft",
        description: String? = "desc",
        order: Int = 1,
        deploymentPolicy: DeploymentPolicy = DeploymentPolicy.IMMEDIATE,
        createdBy: UUID = UUID.randomUUID(),
        modifiedBy: UUID = createdBy,
    ) = Stage(
        id = id,
        name = name,
        description = description,
        order = order,
        deploymentPolicy = deploymentPolicy,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = createdBy,
        modifiedBy = modifiedBy,
    )

    @Test
    fun `findAll maps stages ordered by order`() {
        val stage = persistedStage()
        given(repository.findAll(Sort.by("order"))).willReturn(listOf(stage))

        val result = service.findAll()

        assertEquals(1, result.size)
        assertEquals(stage.id, result[0].id)
        assertEquals(stage.name, result[0].name)
        assertEquals(stage.createdBy, result[0].createdBy)
        assertEquals(stage.modifiedBy, result[0].modifiedBy)
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

        val result = service.create(StageRequest(name = "Draft", description = "desc", order = 1, deploymentPolicy = DeploymentPolicy.IMMEDIATE))

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
            service.create(StageRequest(name = "Draft", description = null, order = 1, deploymentPolicy = DeploymentPolicy.IMMEDIATE))
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

        val result = service.update(stage.id!!, StageRequest(name = "Renamed", description = "new desc", order = 2, deploymentPolicy = DeploymentPolicy.IMMEDIATE))

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
            service.update(id, StageRequest(name = "Renamed", description = null, order = 2, deploymentPolicy = DeploymentPolicy.IMMEDIATE))
        }

        assertEquals(404, exception.statusCode.value())
        verify(repository, never()).save(any())
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
