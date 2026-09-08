// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

data class FieldChange(val old: Any?, val new: Any?)

// Computes a shallow, top-level field diff between two entities' Map representations
// (see AuditRecorder, which builds these via Jackson from each entity's own Response
// DTO — so a relationship embedded there, e.g. a Workload's linked stages, is captured
// as an ordinary field like any other). Used for all three audit actions uniformly:
// CREATED passes before = null (every field reports as newly set), DELETED passes
// after = null (every field reports as removed), UPDATED passes both (only fields that
// actually changed are reported).
object AuditDiff {
    fun compute(before: Map<String, Any?>?, after: Map<String, Any?>?): Map<String, FieldChange> {
        val keys = LinkedHashSet<String>()
        after?.keys?.let(keys::addAll)
        before?.keys?.let(keys::addAll)
        val result = LinkedHashMap<String, FieldChange>()
        for (key in keys) {
            val oldValue = before?.get(key)
            val newValue = after?.get(key)
            if (oldValue != newValue) {
                result[key] = FieldChange(oldValue, newValue)
            }
        }
        return result
    }
}
