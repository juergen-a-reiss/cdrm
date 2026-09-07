// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.springframework.data.jpa.repository.JpaRepository

interface ConfigEntryRepository : JpaRepository<ConfigEntry, String>
