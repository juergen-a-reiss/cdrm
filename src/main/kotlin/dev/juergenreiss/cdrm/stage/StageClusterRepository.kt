// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.stage

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StageClusterRepository : JpaRepository<StageCluster, UUID> {
    fun findByStageId(stageId: UUID): List<StageCluster>
}
