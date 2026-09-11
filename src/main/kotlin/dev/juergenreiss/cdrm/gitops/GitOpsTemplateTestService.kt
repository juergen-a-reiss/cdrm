// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

// Backs the TEMPLATE-mode script editor's "Test" button: runs a script (the editor's own
// buffer, not whatever is saved on the cluster) through GitOpsTemplateEngine using a
// context built from a devops-user-chosen (workload, stage) pair, exactly as
// GitOpsResolver.resolve() would for a real deploy — then, for every edit the script
// returns, checks it against the real git repository (branch/file/YAML-key-path
// existence) via GitCommitClient.checkEdits, without ever writing or pushing anything.
@Service
class GitOpsTemplateTestService(
    private val workloadRepository: WorkloadRepository,
    private val stageRepository: StageRepository,
    private val productRepository: ProductRepository,
    private val templateEngine: GitOpsTemplateEngine,
    private val gitCommitClient: GitCommitClient,
) {
    fun test(request: GitOpsTemplateTestRequest): GitOpsTemplateTestResponse {
        if (request.repositoryUrl.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A git repository must be configured before testing")
        }
        val workload = workloadRepository.findById(request.workloadId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown workload") }
        val stage = stageRepository.findById(request.stageId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stage") }
        val product = productRepository.findById(workload.productId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "This workload's product no longer exists") }
        val baseNamespace = workload.kubernetesNameSpace
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Workload '${workload.name}' has no Kubernetes namespace configured")

        val context = GitOpsTemplateContext(
            gitRepoName = request.repositoryUrl,
            clusterName = request.clusterName,
            namespace = (stage.namespacePrefix ?: "") + baseNamespace,
            productName = product.name,
            stageName = stage.name,
            workloadName = workload.name,
            releaseBinary = request.releaseBinary,
            targetStage = stage.name,
        )

        return when (val result = templateEngine.evaluate(request.script, context)) {
            is GitOpsTemplateResult.Failed -> GitOpsTemplateTestResponse(success = false, reason = result.reason, edits = emptyList())
            is GitOpsTemplateResult.Success -> {
                if (result.edits.isEmpty()) {
                    GitOpsTemplateTestResponse(success = false, reason = "template script returned no edits", edits = emptyList())
                } else {
                    GitOpsTemplateTestResponse(success = true, reason = null, edits = checkAgainstGit(request.repositoryUrl, result.edits))
                }
            }
        }
    }

    private fun checkAgainstGit(repositoryUrl: String, edits: List<GitOpsEdit>): List<GitOpsTemplateTestEdit> =
        when (val outcome = gitCommitClient.checkEdits(repositoryUrl, edits)) {
            is GitOpsEditCheckOutcome.Success -> outcome.checks.map {
                GitOpsTemplateTestEdit(
                    gitBranch = it.edit.branch,
                    filePath = it.edit.filePath,
                    yamlKeyPath = it.edit.yamlKeyPath,
                    value = it.edit.value,
                    branchExists = it.branchExists,
                    fileExists = it.fileExists,
                    yamlKeyPathExists = it.yamlKeyPathExists,
                )
            }
            is GitOpsEditCheckOutcome.Failed -> throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Could not check the edits against '$repositoryUrl': ${outcome.reason}",
            )
            GitOpsEditCheckOutcome.LockBusy -> throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Another git operation is in progress — try again shortly",
            )
        }
}
