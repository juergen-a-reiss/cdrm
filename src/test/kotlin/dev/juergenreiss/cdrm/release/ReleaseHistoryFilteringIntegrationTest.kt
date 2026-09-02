// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// Exercises the Specification-based dynamic filtering and the Criteria-API/date_trunc
// aggregation query against a real Postgres — both are novel enough in this codebase
// (no prior Specification or raw Criteria usage) that trusting them against a mocked
// repository, as the rest of ReleaseServiceTest does, isn't enough; this is the one
// place they actually run against a database and get their WHERE/GROUP BY clauses
// evaluated for real.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReleaseHistoryAggregationRepository::class)
@Testcontainers
class ReleaseHistoryFilteringIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    }

    @Autowired
    private lateinit var releaseHistoryRepository: ReleaseHistoryRepository

    @Autowired
    private lateinit var releaseHistoryAggregationRepository: ReleaseHistoryAggregationRepository

    @Autowired
    private lateinit var stageRepository: StageRepository

    private lateinit var devStage: Stage
    private lateinit var prodStage: Stage

    private fun history(
        productName: String,
        workloadName: String,
        stage: Stage,
        action: ReleaseHistoryAction,
        createdAt: Instant,
        workloadId: UUID? = UUID.randomUUID(),
    ) = ReleaseHistory(
        releaseId = UUID.randomUUID(),
        workloadId = workloadId,
        productId = UUID.randomUUID(),
        productName = productName,
        workloadName = workloadName,
        image = "app:1.0.0",
        stageId = stage.id!!,
        stageName = stage.name,
        pipeline = stage.pipeline,
        action = action,
        createdAt = createdAt,
        createdBy = UUID.randomUUID(),
    ).also { it.createdAt = createdAt }

    @BeforeEach
    fun setUp() {
        val userId = UUID.randomUUID()
        devStage = stageRepository.save(
            Stage(
                pipeline = "main",
                name = "Dev-${UUID.randomUUID()}",
                order = 1,
                deploymentPolicy = DeploymentPolicy.IMMEDIATE,
                createdAt = Instant.now(),
                modifiedAt = Instant.now(),
                createdBy = userId,
                modifiedBy = userId,
            )
        )
        prodStage = stageRepository.save(
            Stage(
                pipeline = "other",
                name = "Prod-${UUID.randomUUID()}",
                order = 2,
                deploymentPolicy = DeploymentPolicy.IMMEDIATE,
                createdAt = Instant.now(),
                modifiedAt = Instant.now(),
                createdBy = userId,
                modifiedBy = userId,
            )
        )
    }

    @Test
    fun `findAll with a Specification filters by product, workload, stage, pipeline and action together`() {
        val now = Instant.now()
        val matching = releaseHistoryRepository.save(
            history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, now)
        )
        releaseHistoryRepository.save(history("Platform", "api", prodStage, ReleaseHistoryAction.PROMOTED, now))
        releaseHistoryRepository.save(history("Payments", "api", devStage, ReleaseHistoryAction.PROMOTED, now))
        releaseHistoryRepository.save(history("Platform", "worker", devStage, ReleaseHistoryAction.PROMOTED, now))
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.ROLLED_BACK, now))

        val filter = ReleaseHistoryFilter(
            productIds = setOf(matching.productId),
            workloadIds = setOf(matching.workloadId!!),
            stageIds = setOf(devStage.id!!),
            pipelines = setOf("main"),
            actions = setOf(ReleaseHistoryAction.PROMOTED),
        )
        val page = releaseHistoryRepository.findAll(releaseHistorySpecification(filter), PageRequest.of(0, 10, Sort.by("createdAt")))

        assertEquals(1, page.totalElements)
        assertEquals(matching.id, page.content[0].id)
    }

    @Test
    fun `findAll excludes a null workloadId row once a workload filter is active`() {
        val now = Instant.now()
        releaseHistoryRepository.save(history("Platform", "deleted-workload", devStage, ReleaseHistoryAction.PROMOTED, now, workloadId = null))
        val someWorkloadId = UUID.randomUUID()
        val matching = releaseHistoryRepository.save(
            history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, now, workloadId = someWorkloadId)
        )

        val filter = ReleaseHistoryFilter(workloadIds = setOf(someWorkloadId))
        val page = releaseHistoryRepository.findAll(releaseHistorySpecification(filter), PageRequest.of(0, 10, Sort.by("createdAt")))

        assertEquals(1, page.totalElements)
        assertEquals(matching.id, page.content[0].id)
    }

    @Test
    fun `findAll applies a case-insensitive free-text search across product, workload, stage and image`() {
        val now = Instant.now()
        val matching = releaseHistoryRepository.save(history("Platform", "checkout-api", devStage, ReleaseHistoryAction.PROMOTED, now))
        releaseHistoryRepository.save(history("Platform", "unrelated", devStage, ReleaseHistoryAction.PROMOTED, now))

        val filter = ReleaseHistoryFilter(search = "CHECKOUT")
        val page = releaseHistoryRepository.findAll(releaseHistorySpecification(filter), PageRequest.of(0, 10, Sort.by("createdAt")))

        assertEquals(1, page.totalElements)
        assertEquals(matching.id, page.content[0].id)
    }

    @Test
    fun `findAll applies the ReBAC allowed-product-names restriction`() {
        val now = Instant.now()
        releaseHistoryRepository.save(history("Payments", "api", devStage, ReleaseHistoryAction.PROMOTED, now))
        val matching = releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, now))

        val filter = ReleaseHistoryFilter(allowedProductNames = setOf("Platform"))
        val page = releaseHistoryRepository.findAll(releaseHistorySpecification(filter), PageRequest.of(0, 10, Sort.by("createdAt")))

        assertEquals(1, page.totalElements)
        assertEquals(matching.id, page.content[0].id)
    }

    @Test
    fun `findAll applies a since cutoff`() {
        val old = Instant.now().minus(400, ChronoUnit.DAYS)
        val recent = Instant.now()
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, old))
        val matching = releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, recent))

        val filter = ReleaseHistoryFilter(since = Instant.now().minus(1, ChronoUnit.DAYS))
        val page = releaseHistoryRepository.findAll(releaseHistorySpecification(filter), PageRequest.of(0, 10, Sort.by("createdAt")))

        assertEquals(1, page.totalElements)
        assertEquals(matching.id, page.content[0].id)
    }

    @Test
    fun `summary groups by month and action, counting rows`() {
        val thisMonth = Instant.now()
        val lastMonth = Instant.now().minus(45, ChronoUnit.DAYS)
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, thisMonth))
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, thisMonth))
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.ROLLED_BACK, thisMonth))
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, lastMonth))

        val summary = releaseHistoryAggregationRepository.summary(ReleaseHistoryFilter(), ReleaseHistoryGroupBy.ACTION)

        val thisMonthKey = "%04d-%02d".format(
            thisMonth.atZone(java.time.ZoneId.systemDefault()).year,
            thisMonth.atZone(java.time.ZoneId.systemDefault()).monthValue,
        )
        val promotedThisMonth = summary.single { it.month == thisMonthKey && it.key == "PROMOTED" }
        val rolledBackThisMonth = summary.single { it.month == thisMonthKey && it.key == "ROLLED_BACK" }
        assertEquals(2L, promotedThisMonth.count)
        assertEquals(1L, rolledBackThisMonth.count)
        assertTrue(summary.size >= 3)
    }

    @Test
    fun `summary groups by product name`() {
        val now = Instant.now()
        releaseHistoryRepository.save(history("Platform", "api", devStage, ReleaseHistoryAction.PROMOTED, now))
        releaseHistoryRepository.save(history("Platform", "worker", devStage, ReleaseHistoryAction.PROMOTED, now))
        releaseHistoryRepository.save(history("Payments", "api", devStage, ReleaseHistoryAction.PROMOTED, now))

        val summary = releaseHistoryAggregationRepository.summary(ReleaseHistoryFilter(), ReleaseHistoryGroupBy.PRODUCT)

        assertEquals(2L, summary.single { it.key == "Platform" }.count)
        assertEquals(1L, summary.single { it.key == "Payments" }.count)
    }
}
