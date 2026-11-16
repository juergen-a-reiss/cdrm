// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.config

import org.springframework.data.jpa.repository.JpaRepository

interface ConfigEntryRepository : JpaRepository<ConfigEntry, String>
