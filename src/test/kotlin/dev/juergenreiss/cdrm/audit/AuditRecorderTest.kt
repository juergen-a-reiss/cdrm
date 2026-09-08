// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import dev.juergenreiss.cdrm.notification.EntityChangedEvent
import dev.juergenreiss.cdrm.product.ProductResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

private data class Snapshot(val name: String, val order: Int)

@Suppress("UNCHECKED_CAST")
private fun parseChanges(json: String): Map<String, Map<String, Any?>> =
    jacksonObjectMapper().readValue(json, Map::class.java) as Map<String, Map<String, Any?>>

@ExtendWith(MockitoExtension::class)
class AuditRecorderTest {

    @Mock
    private lateinit var repository: AuditEntryRepository

    @Mock
    private lateinit var eventPublisher: ApplicationEventPublisher

    private val recorder by lazy { AuditRecorder(repository, eventPublisher) }

    @Test
    fun `recordCreate saves an entry with newState set and every field reported as new in changes`() {
        val actor = UUID.randomUUID()
        recorder.recordCreate(AuditEntityType.CLUSTER, "id-1", "prod", null, Snapshot("prod", 1), actor)

        val captor = ArgumentCaptor.forClass(AuditEntry::class.java)
        verify(repository).save(captor.capture())
        val entry = captor.value

        assertEquals(AuditEntityType.CLUSTER, entry.entityType)
        assertEquals("id-1", entry.entityId)
        assertEquals("prod", entry.entityName)
        assertNull(entry.productName)
        assertEquals(AuditAction.CREATED, entry.action)
        assertEquals(actor, entry.createdBy)
        assertTrue(entry.newState!!.contains("\"name\":\"prod\""))
        assertTrue(entry.changes!!.contains("\"old\":null"))
        assertTrue(entry.changes!!.contains("\"new\":\"prod\""))
    }

    @Test
    fun `recordCreate also publishes an EntityChangedEvent carrying the new state`() {
        val actor = UUID.randomUUID()
        val snapshot = Snapshot("prod", 1)
        recorder.recordCreate(AuditEntityType.CLUSTER, "id-1", "prod", null, snapshot, actor)

        val captor = ArgumentCaptor.forClass(EntityChangedEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())

        assertEquals(AuditEntityType.CLUSTER, captor.value.entityType)
        assertEquals(AuditAction.CREATED, captor.value.action)
        assertEquals("id-1", captor.value.subject)
        assertEquals(snapshot, captor.value.data)
    }

    @Test
    fun `recordDelete publishes an EntityChangedEvent carrying the prior (only available) state`() {
        val actor = UUID.randomUUID()
        val snapshot = Snapshot("prod", 1)
        recorder.recordDelete(AuditEntityType.PRODUCT, "id-1", "prod", null, snapshot, actor)

        val captor = ArgumentCaptor.forClass(EntityChangedEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())

        assertEquals(AuditAction.DELETED, captor.value.action)
        assertEquals(snapshot, captor.value.data)
    }

    @Test
    fun `recordUpdate saves an entry with newState set and only the changed field in changes`() {
        val actor = UUID.randomUUID()
        recorder.recordUpdate(
            AuditEntityType.STAGE, "id-1", "renamed", null,
            Snapshot("original", 1), Snapshot("renamed", 1), actor,
        )

        val captor = ArgumentCaptor.forClass(AuditEntry::class.java)
        verify(repository).save(captor.capture())
        val entry = captor.value

        assertEquals(AuditAction.UPDATED, entry.action)
        assertTrue(entry.newState!!.contains("\"name\":\"renamed\""))
        assertTrue(entry.changes!!.contains("\"name\""))
        assertTrue(!entry.changes!!.contains("\"order\""))
    }

    @Test
    fun `recordDelete saves an entry with newState null and every field reported as removed in changes`() {
        val actor = UUID.randomUUID()
        recorder.recordDelete(AuditEntityType.PRODUCT, "id-1", "prod", null, Snapshot("prod", 1), actor)

        val captor = ArgumentCaptor.forClass(AuditEntry::class.java)
        verify(repository).save(captor.capture())
        val entry = captor.value

        assertEquals(AuditAction.DELETED, entry.action)
        assertNull(entry.newState)
        assertTrue(entry.changes!!.contains("\"old\":\"prod\""))
        assertTrue(entry.changes!!.contains("\"new\":null"))
    }

    @Test
    fun `recordUpdate against a real ProductResponse reports only name and description as changed`() {
        val actor = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val createdBy = UUID.randomUUID()
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")

        fun product(name: String, description: String?, modifiedAt: Instant) = ProductResponse(
            id = productId,
            name = name,
            description = description,
            isGroup = false,
            productGroupId = null,
            stageDeploymentCrons = emptyList(),
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            createdBy = createdBy,
            modifiedBy = actor,
        )

        val before = product("checkout", "old description", createdAt)
        val after = product("checkout-v2", "new description", Instant.parse("2026-01-02T00:00:00Z"))

        recorder.recordUpdate(AuditEntityType.PRODUCT, productId.toString(), after.name, null, before, after, actor)

        val captor = ArgumentCaptor.forClass(AuditEntry::class.java)
        verify(repository).save(captor.capture())
        val changes = parseChanges(captor.value.changes!!)

        assertEquals(setOf("name", "description", "modifiedAt"), changes.keys)
        assertEquals(mapOf("old" to "checkout", "new" to "checkout-v2"), changes["name"])
        assertEquals(mapOf("old" to "old description", "new" to "new description"), changes["description"])
        // Unchanged fields (id, isGroup, productGroupId, stageDeploymentCrons, createdAt,
        // createdBy, modifiedBy) must not appear at all — only what actually differs.
        assertTrue(!changes.containsKey("id"))
        assertTrue(!changes.containsKey("isGroup"))
        assertTrue(!changes.containsKey("createdBy"))
    }

    @Test
    fun `recordCreate for a WORKLOAD row carries the owning product's name`() {
        val actor = UUID.randomUUID()
        recorder.recordCreate(AuditEntityType.WORKLOAD, "id-1", "platform-api", "Platform", Snapshot("platform-api", 1), actor)

        val captor = ArgumentCaptor.forClass(AuditEntry::class.java)
        verify(repository).save(captor.capture())

        assertEquals("Platform", captor.value.productName)
    }
}
