// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

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
