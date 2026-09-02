// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.common

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

// Parsed form of a "sort" query parameter, e.g. "name,desc" — the same "key,direction"
// shape the frontend's own sort-by state already uses, so the API client can pass it
// straight through with no translation.
data class SortSpec(val key: String, val descending: Boolean) {
    companion object {
        fun parse(sort: String?, default: SortSpec): SortSpec {
            if (sort.isNullOrBlank()) return default
            val parts = sort.split(",", limit = 2)
            val key = parts[0].trim()
            val descending = parts.getOrNull(1)?.trim()?.equals("desc", ignoreCase = true) ?: false
            return SortSpec(key, descending)
        }
    }
}

// Sorts a response list in memory against an allow-list of ascending comparators, one
// per frontend-facing sort key — for the lists that aren't expected to grow large
// (everything except release_history, which sorts at the database level instead via
// its own indices). An unknown key is a 400, not a silent fallback to the default,
// so a typo in a future frontend column key fails loudly instead of quietly sorting
// wrong.
fun <T> List<T>.sortedBySpec(spec: SortSpec, comparators: Map<String, Comparator<T>>): List<T> {
    val comparator = comparators[spec.key]
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sort key '${spec.key}'")
    return if (spec.descending) sortedWith(comparator.reversed()) else sortedWith(comparator)
}
