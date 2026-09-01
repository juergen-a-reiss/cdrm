// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.security

// The action names the cdrm-release-actions ReBAC claim recognizes (see README).
// Distinct from ReleaseHistoryAction: PROMOTE covers both create() (landing at a
// workload's first stage) and promote() (moving to the next stage), matching the
// claim's own example ("promote: dev, qa" allows creating and promoting to qa).
enum class ReleaseActionClaim {
    PROMOTE,
    ROLLBACK,
    REDEPLOY,
    DELETE,
    EDIT,
}
