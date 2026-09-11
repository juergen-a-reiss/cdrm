// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/gitops")
class GitOpsTemplateTestController(private val service: GitOpsTemplateTestService) {

    @PostMapping("/templates/test")
    fun test(@RequestBody request: GitOpsTemplateTestRequest): GitOpsTemplateTestResponse = service.test(request)
}
