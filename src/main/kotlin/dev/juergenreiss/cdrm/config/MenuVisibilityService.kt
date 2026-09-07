// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import dev.juergenreiss.cdrm.security.RebacContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

private const val MENU_VISIBILITY_KEY = "menu-visibility"

@Service
@Transactional(readOnly = true)
class MenuVisibilityService(
    private val repository: ConfigEntryRepository,
    private val rebac: RebacContext,
) {

    private val log = LoggerFactory.getLogger(MenuVisibilityService::class.java)
    private val objectMapper = jacksonObjectMapper()

    // The union of whatever the caller's roles are whitelisted for, plus "configuration"
    // hardcoded on for cdrm-devops — never driven by the JSON below, so a bad/missing
    // edit there can't lock devops out of the only screen that can fix it.
    fun effectiveMenuKeys(): Set<String> {
        val whitelist = readWhitelist()
        val visible = rebac.currentRoles
            .flatMap { role -> whitelist[role].orEmpty() }
            .filterTo(mutableSetOf()) { it in MenuEntries.ASSIGNABLE }
        if (rebac.isDevops) {
            visible += MenuEntries.CONFIGURATION
        }
        return visible
    }

    private fun readWhitelist(): Map<String, List<String>> {
        val entry = repository.findById(MENU_VISIBILITY_KEY).orElse(null) ?: return emptyMap()
        return try {
            objectMapper.readValue<Map<String, List<String>>>(entry.value)
        } catch (e: Exception) {
            log.warn("Malformed '{}' config entry, treating menu whitelist as empty: {}", MENU_VISIBILITY_KEY, e.message)
            emptyMap()
        }
    }
}
