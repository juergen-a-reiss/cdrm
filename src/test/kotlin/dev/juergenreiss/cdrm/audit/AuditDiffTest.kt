// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuditDiffTest {

    @Test
    fun `CREATED-shaped diff (before null) reports every field as newly set`() {
        val after = mapOf("name" to "prod", "order" to 1)

        val diff = AuditDiff.compute(before = null, after = after)

        assertEquals(FieldChange(old = null, new = "prod"), diff["name"])
        assertEquals(FieldChange(old = null, new = 1), diff["order"])
        assertEquals(2, diff.size)
    }

    @Test
    fun `DELETED-shaped diff (after null) reports every field as removed`() {
        val before = mapOf("name" to "prod", "order" to 1)

        val diff = AuditDiff.compute(before = before, after = null)

        assertEquals(FieldChange(old = "prod", new = null), diff["name"])
        assertEquals(FieldChange(old = 1, new = null), diff["order"])
        assertEquals(2, diff.size)
    }

    @Test
    fun `UPDATED diff reports only fields that actually changed`() {
        val before = mapOf("name" to "prod", "order" to 1, "description" to "same")
        val after = mapOf("name" to "prod-renamed", "order" to 1, "description" to "same")

        val diff = AuditDiff.compute(before, after)

        assertEquals(setOf("name"), diff.keys)
        assertEquals(FieldChange(old = "prod", new = "prod-renamed"), diff["name"])
    }

    @Test
    fun `identical before and after produce an empty diff`() {
        val state = mapOf("name" to "prod", "order" to 1)

        val diff = AuditDiff.compute(state, state.toMap())

        assertTrue(diff.isEmpty())
    }

    @Test
    fun `a field only present in one side (e-g- a schema change) still reports its change`() {
        val before = mapOf("name" to "prod")
        val after = mapOf("name" to "prod", "newField" to "value")

        val diff = AuditDiff.compute(before, after)

        assertEquals(FieldChange(old = null, new = "value"), diff["newField"])
        assertNull(diff["name"])
    }

    @Test
    fun `both sides null produces an empty diff`() {
        val diff = AuditDiff.compute(before = null, after = null)

        assertTrue(diff.isEmpty())
    }
}
