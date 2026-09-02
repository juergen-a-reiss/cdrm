// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

@Entity
@Table(name = "stage")
@EntityListeners(AuditingEntityListener::class)
class Stage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    var pipeline: String,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column
    var description: String? = null,

    @Column(name = "stage_order", nullable = false)
    var order: Int,

    @Column(name = "deployment_policy", nullable = false)
    @Enumerated(EnumType.STRING)
    var deploymentPolicy: DeploymentPolicy,

    // Kubeconfig context name — resolved against the server's kubeconfig at deploy time.
    // Required only for stages that will receive kubernetes-managed workloads.
    @Column(name = "kubernetes_context")
    var kubernetesContext: String? = null,

    // Prepended to a workload's kubernetesNameSpace when deploying into this stage.
    // Only needed when multiple stages share the same cluster (kubernetesContext) and
    // would otherwise collide on namespace, e.g. a single minikube for all stages.
    @Column(name = "namespace_prefix")
    var namespacePrefix: String? = null,

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
