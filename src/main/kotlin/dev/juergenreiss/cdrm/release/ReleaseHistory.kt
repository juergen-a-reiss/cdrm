// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

// No FK to Release on purpose: this must survive deletion of the release it describes.
@Entity
@Table(name = "release_history")
@EntityListeners(AuditingEntityListener::class)
class ReleaseHistory(

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "release_id", nullable = false)
    val releaseId: UUID,

    // Denormalized (not an FK) for the same reason as binaryUrl: a workload can be
    // deleted once none of its releases reference it anymore, but by-workload/by-product
    // statistics still need to attribute this entry correctly after that happens. Null
    // only for rows migrated from before this column existed whose release was already
    // deleted by then, so the backfill had nothing to join against.
    @Column(name = "workload_id")
    val workloadId: UUID? = null,

    @Column(name = "binary_url", nullable = false)
    val binaryUrl: String,

    @Column(name = "stage_id", nullable = false)
    val stageId: UUID,

    @Column(name = "action", nullable = false)
    @Enumerated(EnumType.STRING)
    val action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED,

    // Null while a SCHEDULED-policy deployment hasn't fired yet; set immediately for
    // IMMEDIATE policy, later by the scheduler job otherwise.
    @Column(name = "deployed_at")
    var deployedAt: Instant? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    val createdBy: UUID,
)
