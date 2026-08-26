// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

@Entity
@Table(name = "workload")
@EntityListeners(AuditingEntityListener::class)
class Workload(

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(name = "product_id", nullable = false)
    var productId: UUID,

    @Column
    var description: String? = null,

    @Column(nullable = false)
    var kubernetes: Boolean,

    @Column(name = "kubernetes_kind")
    @Enumerated(EnumType.STRING)
    var kubernetesKind: KubernetesKind? = null,

    @Column(name = "name_space")
    var kubernetesNameSpace: String? = null,

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

