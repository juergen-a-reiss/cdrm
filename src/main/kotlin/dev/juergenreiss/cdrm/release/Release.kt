// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

@Entity
@Table(name = "release")
@EntityListeners(AuditingEntityListener::class)
class Release(

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "binary_url", nullable = false)
    val binaryUrl: String,

    @Column
    var description: String? = null,

    @Column(name = "workload_id", nullable = false)
    var workloadId: UUID,

    @Column(name = "current_stage_id", nullable = false)
    var currentStageId: UUID,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @LastModifiedDate
    @Column(nullable = false)
    var modifiedAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    var createdBy: UUID,

    @Column(nullable = false)
    var modifiedBy: UUID,
)
