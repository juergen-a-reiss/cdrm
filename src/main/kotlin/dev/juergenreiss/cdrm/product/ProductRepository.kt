// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.product

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface ProductRepository : JpaRepository<Product, UUID> {
    fun existsByProductGroupId(productGroupId: UUID): Boolean
    fun findByProductGroupIdIn(productGroupIds: Collection<UUID>): List<Product>
}