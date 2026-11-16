// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.workload

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkloadStageRepository : JpaRepository<WorkloadStage, UUID> {
    fun findByWorkloadId(workloadId: UUID): List<WorkloadStage>
    fun findByWorkloadIdIn(workloadIds: Collection<UUID>): List<WorkloadStage>
}
