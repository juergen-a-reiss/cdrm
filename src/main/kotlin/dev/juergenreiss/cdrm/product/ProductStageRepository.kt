// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductStageRepository : JpaRepository<ProductStage, UUID> {
    fun findByProductId(productId: UUID): List<ProductStage>
    fun findByProductIdAndStageId(productId: UUID, stageId: UUID): ProductStage?
}
