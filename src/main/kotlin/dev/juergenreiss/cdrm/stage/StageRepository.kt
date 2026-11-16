// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.stage

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface StageRepository : JpaRepository<Stage, UUID>
