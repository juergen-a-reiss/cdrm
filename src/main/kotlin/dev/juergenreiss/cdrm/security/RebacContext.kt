// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.security

import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

// Reads the cdrm-* ReBAC claims (see README's "ReBAC" section) off the current
// request's JWT. Stateless — every property re-reads SecurityContextHolder, so this is
// safe as an ordinary singleton bean rather than request-scoped, the same way
// AuditConfig's auditorProvider() reads it fresh each time.
//
// A null result for an attribute means the claim is absent — per the spec, ReBAC does
// not apply for that attribute, so the caller should fall back to its own existing
// (role-based) default rather than treating null as "nothing allowed".
//
// cdrm-devops is exempt from all of it: the infrastructure-admin role, expected to see
// and do everything regardless of what ReBAC attributes it happens to carry.
@Component
class RebacContext {

    private val log = LoggerFactory.getLogger(RebacContext::class.java)

    val isDevops: Boolean
        get() = hasRole("cdrm-devops")

    // For a caller's role-based fallback default when cdrm-release-actions isn't set —
    // e.g. "cdrm-productowner" for promote/rollback/redeploy, matching today's rules.
    fun hasRole(role: String): Boolean =
        SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == "ROLE_$role" } ?: false

    val allowedProducts: Set<String>?
        get() = claimList(PRODUCTS_CLAIM)

    val allowedWorkloads: Set<String>?
        get() = claimList(WORKLOADS_CLAIM)

    // Null if the claim is absent. Entries that don't parse as "action: stage, stage"
    // are logged and skipped rather than failing the whole claim.
    val allowedReleaseActions: Map<ReleaseActionClaim, Set<String>>?
        get() {
            val raw = jwt()?.getClaimAsStringList(RELEASE_ACTIONS_CLAIM) ?: return null
            val result = mutableMapOf<ReleaseActionClaim, MutableSet<String>>()
            for (entry in raw) {
                val (action, stages) = parseEntry(entry) ?: continue
                result.getOrPut(action) { mutableSetOf() }.addAll(stages)
            }
            return result
        }

    fun canSeeProduct(productName: String): Boolean =
        isDevops || allowedProducts?.contains(productName) ?: true

    fun canSeeWorkload(productName: String, workloadName: String): Boolean =
        isDevops || (canSeeProduct(productName) && (allowedWorkloads?.contains(workloadName) ?: true))

    // Null when cdrm-release-actions isn't set on this user, or is set but has no entry
    // at all for this specific action — the caller must fall back to its own role-based
    // default in either case, since ReBAC simply doesn't apply to this action then, not
    // "deny". Once the action does have an entry, it's authoritative for every stage:
    // listed stages are allowed, every other stage is denied (false, not null) — it can
    // grant (a role with no baseline release permission) as well as narrow (a role that
    // otherwise has full access).
    fun allowsReleaseAction(action: ReleaseActionClaim, stageName: String): Boolean? {
        if (isDevops) return true
        val allowed = allowedReleaseActions ?: return null
        val stages = allowed[action] ?: return null
        return stages.contains(stageName)
    }

    private fun parseEntry(entry: String): Pair<ReleaseActionClaim, List<String>>? {
        val separator = entry.indexOf(':')
        if (separator < 0) {
            log.warn("Ignoring malformed cdrm-release-actions entry (expected 'action: stage, stage'): '{}'", entry)
            return null
        }
        val actionName = entry.substring(0, separator).trim().uppercase()
        val action = ReleaseActionClaim.entries.find { it.name == actionName }
        if (action == null) {
            log.warn("Ignoring cdrm-release-actions entry with unknown action '{}': '{}'", actionName, entry)
            return null
        }
        val stages = entry.substring(separator + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return action to stages
    }

    private fun claimList(name: String): Set<String>? = jwt()?.getClaimAsStringList(name)?.toSet()

    private fun jwt(): Jwt? = SecurityContextHolder.getContext().authentication?.principal as? Jwt

    companion object {
        private const val PRODUCTS_CLAIM = "cdrm-products"
        private const val WORKLOADS_CLAIM = "cdrm-workloads"
        private const val RELEASE_ACTIONS_CLAIM = "cdrm-release-actions"
    }
}
