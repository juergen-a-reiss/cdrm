// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

// Keys a role can be whitelisted for via the "menu-visibility" config entry. Must match
// the `key` of each entry in the frontend's App.vue navItems — anything else found in
// the JSON (a stale key, a typo) is silently ignored rather than leaking an undefined
// menu entry. CONFIGURATION is deliberately not assignable: it is hardcoded visible to
// cdrm-devops only (see MenuVisibilityService) so a bad edit here can never lock devops
// out of the one screen that can fix it.
object MenuEntries {
    const val CONFIGURATION = "configuration"

    val ASSIGNABLE = setOf("clusters", "stages", "products", "workloads", "releases", "release-history")
}
