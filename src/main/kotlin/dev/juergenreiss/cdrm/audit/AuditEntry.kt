// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

// Never RELEASE — a release's own lifecycle is already fully covered by release_history
// (see ReleaseHistory); this is for the entities that had no audit trail at all.
enum class AuditEntityType { CLUSTER, STAGE, PRODUCT, WORKLOAD, CONFIG }

enum class AuditAction { CREATED, UPDATED, DELETED }

// One row per create/update/delete of a Cluster/Stage/Product/Workload/ConfigEntry —
// written by AuditRecorder, synchronously, in the same transaction as the change itself
// (unlike the best-effort Kafka release notifications: an audit write failing must fail
// the whole operation, not be silently swallowed). Read-only from the API's perspective
// — see AuditService/AuditController; nothing ever updates or deletes a row here.
@Entity
@Table(name = "audit_entry")
@EntityListeners(AuditingEntityListener::class)
class AuditEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "entity_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val entityType: AuditEntityType,

    // A plain string, not a UUID column — every entity type here has a UUID id except
    // CONFIG, whose id is its string key.
    @Column(name = "entity_id", nullable = false)
    val entityId: String,

    @Column(name = "entity_name", nullable = false)
    val entityName: String,

    // Only set for WORKLOAD rows (the workload's owning product) — see ReBAC filtering
    // in AuditSpecifications.
    @Column(name = "product_name")
    val productName: String? = null,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val action: AuditAction,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_state", columnDefinition = "jsonb")
    val newState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val changes: String? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    val createdBy: UUID,
)
