// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.release

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface ReleaseRepository : JpaRepository<Release, UUID> {
    fun findByWorkloadId(workloadId: UUID): List<Release>
}