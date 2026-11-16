// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.workload

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface WorkloadRepository : JpaRepository<Workload, UUID> {
    fun findByProductId(productId: UUID): List<Workload>
    fun findByProductIdIn(productIds: Collection<UUID>): List<Workload>
}