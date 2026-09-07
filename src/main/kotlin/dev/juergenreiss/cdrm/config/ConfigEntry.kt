// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

// Generic admin-editable key/value store. Each row's value is raw JSON text whose shape
// is owned by whichever feature reads that key (e.g. MenuVisibilityService for
// "menu-visibility") rather than by this entity — a shared type here would force every
// future config key into the same shape.
@Entity
@Table(name = "config")
@EntityListeners(AuditingEntityListener::class)
class ConfigEntry(
    @Id
    @Column(name = "config_key", nullable = false, updatable = false)
    val key: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_value", nullable = false, columnDefinition = "jsonb")
    var value: String,

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
