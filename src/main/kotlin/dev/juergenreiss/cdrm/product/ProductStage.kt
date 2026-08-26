// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

// Sparse, like workload_stage: a row only exists once a product has configured a
// deployment time for that stage.
@Entity
@Table(name = "product_stage")
class ProductStage(

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "product_id", nullable = false)
    val productId: UUID,

    @Column(name = "stage_id", nullable = false)
    val stageId: UUID,

    @Column(name = "deployment_cron", nullable = false)
    var deploymentCron: String,
)
