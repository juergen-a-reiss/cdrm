// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

// The two independent tracks a release_history row's deploy/verification outcome is
// tracked on, per docs/deployment-status-state-machine.odg — computed here rather than
// in the frontend so there's exactly one place that knows how the underlying columns
// (deployedAt/deployError/deploymentFinished/deploymentFailed/gitopsError/
// gitopsRetryCount/rolloutStartedAt/replacedAt) combine into a displayable state.

// NOT_APPLICABLE: this row's stage/namespace isn't GitOps-managed — the UI hides this
// column entirely rather than showing it empty.
enum class GitOpsStatus {
    NOT_APPLICABLE,
    PENDING,
    PUSH_SUCCEEDED,
    PUSH_FAILED_RETRYING,
    PUSH_FAILED,
}

// NOT_APPLICABLE: the workload isn't a Kubernetes workload — same hide-the-column
// treatment as GitOpsStatus.NOT_APPLICABLE.
enum class KubernetesStatus {
    NOT_APPLICABLE,
    NOT_STARTED,
    AWAITING_CLUSTER_SYNC,
    ROLLING_OUT,
    HEALTHY,
    FAILED,
    REPLACED,
}

fun ReleaseHistory.gitOpsStatus(): GitOpsStatus {
    if (!gitOpsManaged) return GitOpsStatus.NOT_APPLICABLE
    return when {
        deployedAt != null -> GitOpsStatus.PUSH_SUCCEEDED
        // A GitOps row that gave up (see DeploymentSchedulerJob.MAX_GITOPS_RETRIES) has
        // deploymentFinished/deploymentFailed set despite deployedAt staying null — that
        // combination only ever means this, never a Kubernetes-side failure, since a
        // non-GitOps row always has deployedAt set before deploymentFinished can be.
        deploymentFinished != null -> GitOpsStatus.PUSH_FAILED
        gitopsError != null -> GitOpsStatus.PUSH_FAILED_RETRYING
        else -> GitOpsStatus.PENDING
    }
}

fun ReleaseHistory.kubernetesStatus(): KubernetesStatus {
    if (!kubernetesManaged) return KubernetesStatus.NOT_APPLICABLE
    if (replacedAt != null) return KubernetesStatus.REPLACED
    if (deployedAt == null) return KubernetesStatus.NOT_STARTED
    if (deploymentFinished == null) {
        return if (rolloutStartedAt != null) KubernetesStatus.ROLLING_OUT else KubernetesStatus.AWAITING_CLUSTER_SYNC
    }
    return if (deploymentFailed) KubernetesStatus.FAILED else KubernetesStatus.HEALTHY
}
