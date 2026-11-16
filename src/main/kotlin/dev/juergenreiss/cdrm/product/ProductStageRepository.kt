// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.product

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductStageRepository : JpaRepository<ProductStage, UUID> {
    fun findByProductId(productId: UUID): List<ProductStage>
    fun findByProductIdAndStageId(productId: UUID, stageId: UUID): ProductStage?
    fun findByStageIdIn(stageIds: Collection<UUID>): List<ProductStage>
}
