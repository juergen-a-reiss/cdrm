// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Just the configured URLs — never credentials — so the cluster-editing screen can
// offer a dropdown instead of a free-text field for a cluster's/namespace's gitRepo,
// which must match one of these by exact string equality anyway (see GitOpsProperties).
@RestController
@RequestMapping("/gitops")
class GitOpsRepositoryController(private val properties: GitOpsProperties) {

    @GetMapping("/repositories")
    fun repositories(): List<String> = properties.repositories.map { it.url }
}
