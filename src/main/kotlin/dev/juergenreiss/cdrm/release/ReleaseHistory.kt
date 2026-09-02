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

    // Denormalized (not an FK) for the same reason as image: a workload can be deleted
    // once none of its releases reference it anymore, but by-workload/by-product
    // statistics still need to attribute this entry correctly after that happens. Null
    // only for rows migrated from before this column existed whose release was already
    // deleted by then, so the backfill had nothing to join against.
    @Column(name = "workload_id")
    val workloadId: UUID? = null,

    // Also denormalized, for the same reason as workloadId.
    @Column(name = "product_id", nullable = false)
    val productId: UUID,

    // Snapshotted at insert time (like image), not resolved via a live join — so the
    // UI can always display and filter/sort on these even after the product/workload/
    // stage that produced this row has since been deleted.
    @Column(name = "product_name", nullable = false)
    val productName: String,

    @Column(name = "workload_name", nullable = false)
    val workloadName: String,

    // Container image reference (not a URL) — same format as Release.image.
    @Column(name = "image", nullable = false)
    val image: String,

    @Column(name = "stage_id", nullable = false)
    val stageId: UUID,

    @Column(name = "stage_name", nullable = false)
    val stageName: String,

    // Also denormalized (see productName) — a snapshot of the stage's pipeline at
    // insert time, so it survives the stage being deleted or later reassigned to a
    // different pipeline. Null only for rows from before this column existed.
    @Column(name = "pipeline")
    val pipeline: String? = null,

    @Column(name = "action", nullable = false)
    @Enumerated(EnumType.STRING)
    val action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED,

    // Null while a SCHEDULED-policy deployment hasn't fired yet; set immediately for
    // IMMEDIATE policy, later by the scheduler job otherwise.
    @Column(name = "deployed_at")
    var deployedAt: Instant? = null,

    // Reason the most recent deploy attempt for this row failed; cleared once it
    // eventually succeeds. Null while still unattempted (a SCHEDULED-policy row waiting
    // for its cron time).
    @Column(name = "deploy_error")
    var deployError: String? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    val createdBy: UUID,
)
