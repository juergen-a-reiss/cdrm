// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import java.util.UUID

// Powers the "Test" button on a namespace's TEMPLATE-mode script editor (see
// GitOpsTemplateTestService) — script is whatever is currently in the editor's buffer,
// never what's saved on the cluster, so a devops user can iterate before saving.
// workloadId/stageId stand in for a real deploy's (workload, stage) pair, supplying the
// context variables a real deploy would (productName, namespace, ...); releaseBinary is
// free text since there may be no release yet to pick one from. repositoryUrl is
// whatever the form currently resolves for this namespace (its own gitRepo override, or
// the cluster's default) — computed client-side, the same value a real deploy would use
// as gitRepoName.
data class GitOpsTemplateTestRequest(
    val script: String,
    val repositoryUrl: String,
    val clusterName: String,
    val workloadId: UUID,
    val stageId: UUID,
    val releaseBinary: String,
)

// One of the script's returned edits, annotated with whether it would actually apply —
// never written, just checked against the target repo (see GitCommitClient.checkEdits).
data class GitOpsTemplateTestEdit(
    val gitBranch: String,
    val filePath: String,
    val yamlKeyPath: String,
    val value: String,
    val branchExists: Boolean,
    val fileExists: Boolean,
    val yamlKeyPathExists: Boolean,
)

// success=false means the script itself failed (thrown error, syntax error, non-array
// return, ...) or returned no edits at all — reason carries why, edits is then always
// empty. success=true means the script ran and returned at least one edit; whether each
// one would actually apply is per-edit (branchExists/fileExists/yamlKeyPathExists), not
// rolled up into this top-level flag.
data class GitOpsTemplateTestResponse(
    val success: Boolean,
    val reason: String?,
    val edits: List<GitOpsTemplateTestEdit>,
)
