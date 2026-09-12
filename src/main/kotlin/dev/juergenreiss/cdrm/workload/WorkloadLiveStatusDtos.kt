// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import java.time.Instant

// NOT_APPLICABLE: non-Kubernetes workload, or the stage/workload is missing the
// Kubernetes config a live read needs (context/namespace/kind). NOT_FOUND: the
// Deployment/StatefulSet doesn't exist in the cluster (never deployed here, or deleted
// outside cdrm). UNREACHABLE: the live read itself failed (cluster/API unreachable,
// auth failure, ...) — error carries why. OK: a real read succeeded.
enum class LiveStatusState { NOT_APPLICABLE, NOT_FOUND, UNREACHABLE, OK }

data class LivePodInfo(
    val name: String,
    val image: String?,
    val ready: Boolean,
    val restartCount: Int,
    val runningSince: Instant?,
)

// Live current state of a workload's Deployment/StatefulSet at a given stage, read
// on-demand from the cluster (see KubernetesDeploymentClient.getLiveStatus) — every
// field beyond state/error is null unless state == OK.
data class LiveStatusResponse(
    val state: LiveStatusState,
    val error: String?,
    val desiredReplicas: Int?,
    val readyReplicas: Int?,
    val totalRestartCount: Int?,
    // The manifest's own declared image — can differ from what's actually running on a
    // pod mid-rollout (see pods for that).
    val image: String?,
    val resourceCreatedAt: Instant?,
    // The oldest still-running pod's own runningSince — a single crashed-and-restarted
    // replica among otherwise-stable ones shouldn't reset the headline uptime to "just
    // now" (that pod's own restart is still visible via its row in pods).
    val uptimeSince: Instant?,
    val pods: List<LivePodInfo>,
)
