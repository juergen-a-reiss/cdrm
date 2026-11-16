// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.config

import java.time.Instant
import java.util.*

data class ConfigUpdateRequest(
    val value: Any,
)

data class ConfigResponse(
    val key: String,
    val value: Any,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)
