// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

// Exercises auditSpecification()'s ReBAC predicate against a real Postgres — the "OR
// across three shapes of row" logic (see its own comments) is novel/tricky enough not
// to trust against a mocked repository alone, the same reasoning
// ReleaseHistoryFilteringIntegrationTest already applies to its own Specification.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuditSpecificationsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:18"))
    }

    @Autowired
    private lateinit var repository: AuditEntryRepository

    private lateinit var clusterEntry: AuditEntry
    private lateinit var platformProductEntry: AuditEntry
    private lateinit var paymentsProductEntry: AuditEntry
    private lateinit var platformApiWorkloadEntry: AuditEntry
    private lateinit var platformWorkerWorkloadEntry: AuditEntry
    private lateinit var paymentsWorkloadEntry: AuditEntry

    private fun entry(type: AuditEntityType, name: String, productName: String? = null) = AuditEntry(
        entityType = type,
        entityId = UUID.randomUUID().toString(),
        entityName = name,
        productName = productName,
        action = AuditAction.CREATED,
        newState = "{}",
        changes = "{}",
        createdAt = Instant.now(),
        createdBy = UUID.randomUUID(),
    )

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
        clusterEntry = repository.save(entry(AuditEntityType.CLUSTER, "minikube"))
        platformProductEntry = repository.save(entry(AuditEntityType.PRODUCT, "Platform"))
        paymentsProductEntry = repository.save(entry(AuditEntityType.PRODUCT, "Payments"))
        platformApiWorkloadEntry = repository.save(entry(AuditEntityType.WORKLOAD, "platform-api", "Platform"))
        platformWorkerWorkloadEntry = repository.save(entry(AuditEntityType.WORKLOAD, "platform-worker", "Platform"))
        paymentsWorkloadEntry = repository.save(entry(AuditEntityType.WORKLOAD, "payments-gateway", "Payments"))
    }

    private fun idsFor(filter: AuditFilter): Set<UUID> =
        repository.findAll(auditSpecification(filter), PageRequest.of(0, 50)).content.mapNotNull { it.id }.toSet()

    @Test
    fun `with no ReBAC claims set, every row is visible`() {
        val ids = idsFor(AuditFilter())

        assertEquals(6, ids.size)
    }

    @Test
    fun `cdrm-products restricts PRODUCT rows by name, and WORKLOAD rows by their product's name, but never CLUSTER rows`() {
        val ids = idsFor(AuditFilter(allowedProductNames = setOf("Platform")))

        assertEquals(
            setOf(clusterEntry.id, platformProductEntry.id, platformApiWorkloadEntry.id, platformWorkerWorkloadEntry.id),
            ids,
        )
    }

    @Test
    fun `cdrm-workloads further restricts WORKLOAD rows by the workload's own name`() {
        val ids = idsFor(AuditFilter(allowedProductNames = setOf("Platform"), allowedWorkloadNames = setOf("platform-api")))

        assertEquals(setOf(clusterEntry.id, platformProductEntry.id, platformApiWorkloadEntry.id), ids)
    }

    @Test
    fun `entityType filter narrows to just that type, on top of ReBAC`() {
        val ids = idsFor(AuditFilter(entityTypes = setOf(AuditEntityType.WORKLOAD), allowedProductNames = setOf("Platform")))

        assertEquals(setOf(platformApiWorkloadEntry.id, platformWorkerWorkloadEntry.id), ids)
    }

    @Test
    fun `search matches entityName case-insensitively`() {
        val ids = idsFor(AuditFilter(search = "PLATFORM-API"))

        assertEquals(setOf(platformApiWorkloadEntry.id), ids)
    }
}
