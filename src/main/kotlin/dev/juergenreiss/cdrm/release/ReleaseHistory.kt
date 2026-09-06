// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.*

// No FK to Release on purpose: this must survive deletion of the release it describes.
@Entity
@Table(name = "release_history")
@EntityListeners(AuditingEntityListener::class)
class ReleaseHistory(

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "release_id", nullable = false)
    val releaseId: UUID,

    // Denormalized (not an FK) for the same reason as image: a workload can be deleted
    // once none of its releases reference it anymore, but by-workload/by-product
    // statistics still need to attribute this entry correctly after that happens. Null
    // only for rows migrated from before this column existed whose release was already
    // deleted by then, so the backfill had nothing to join against.
    @Column(name = "workload_id")
    val workloadId: UUID? = null,

    // Also denormalized, for the same reason as workloadId.
    @Column(name = "product_id", nullable = false)
    val productId: UUID,

    // Snapshotted at insert time (like image), not resolved via a live join — so the
    // UI can always display and filter/sort on these even after the product/workload/
    // stage that produced this row has since been deleted.
    @Column(name = "product_name", nullable = false)
    val productName: String,

    @Column(name = "workload_name", nullable = false)
    val workloadName: String,

    // Container image reference (not a URL) — same format as Release.image.
    @Column(name = "image", nullable = false)
    val image: String,

    @Column(name = "stage_id", nullable = false)
    val stageId: UUID,

    @Column(name = "stage_name", nullable = false)
    val stageName: String,

    // Also denormalized (see productName) — a snapshot of the stage's pipeline at
    // insert time, so it survives the stage being deleted or later reassigned to a
    // different pipeline. Null only for rows from before this column existed.
    @Column(name = "pipeline")
    val pipeline: String? = null,

    @Column(name = "action", nullable = false)
    @Enumerated(EnumType.STRING)
    val action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED,

    // Null while a SCHEDULED-policy deployment hasn't fired yet; set immediately for
    // IMMEDIATE policy, later by the scheduler job otherwise.
    @Column(name = "deployed_at")
    var deployedAt: Instant? = null,

    // Reason the most recent deploy attempt for this row failed; cleared once it
    // eventually succeeds. Null while still unattempted (a SCHEDULED-policy row waiting
    // for its cron time).
    @Column(name = "deploy_error")
    var deployError: String? = null,

    // When the async rollout-verification check (DeploymentVerificationJob) concluded,
    // success or failure. Set immediately (= deployedAt) for non-Kubernetes workloads,
    // since there's nothing to verify. Null while the deploy itself hasn't succeeded yet,
    // or while a Kubernetes rollout is still within its 5-minute verification window.
    @Column(name = "deployment_finished")
    var deploymentFinished: Instant? = null,

    // True once verification concludes the rollout did not succeed within the grace
    // period (see deployError for why). A release can't be promoted while its current
    // stage's latest deployment is unfinished or failed — it can still be redeployed
    // there.
    @Column(name = "deployment_failed", nullable = false)
    var deploymentFailed: Boolean = false,

    // Snapshotted at insert time — see GitOpsResolver. Whether this row's deploy went
    // through GitOps rather than a direct Kubernetes patch; gates which of gitopsError/
    // deployError DeploymentExecutor's result is written into (see DeploymentSchedulerJob).
    @Column(name = "gitops_managed", nullable = false)
    val gitOpsManaged: Boolean = false,

    // Also snapshotted at insert time — whether the workload was a Kubernetes workload.
    // Purely a display concern (whether the "Kubernetes Status" column applies at all);
    // actual deployment behavior always reads workload.kubernetes live.
    @Column(name = "kubernetes_managed", nullable = false)
    val kubernetesManaged: Boolean = false,

    // Reason the most recent GitOps push attempt failed; cleared once it succeeds.
    // Distinct from deployError, which is the Kubernetes rollout's own error.
    @Column(name = "gitops_error")
    var gitopsError: String? = null,

    // Consecutive failed GitOps push attempts, capped at 5 (see DeploymentSchedulerJob) —
    // on the 5th the row is given up on rather than retried forever.
    @Column(name = "gitops_retry_count", nullable = false)
    var gitopsRetryCount: Int = 0,

    // First time verification observed the new image actually running on a pod — the
    // 5-minute rollout grace period (DeploymentVerificationJob) is measured from here,
    // not deployedAt, so an unsynced GitOps deploy waiting on ArgoCD/a human never times
    // out on its own; only a rollout that visibly started and then stalls does.
    @Column(name = "rollout_started_at")
    var rolloutStartedAt: Instant? = null,

    // Set when another deploy lands on this row's (workload, stage) while this one is
    // still awaiting cluster sync or mid-rollout — superseding it instead of blocking
    // the new deploy (see ReleaseService.requireNoConcurrentDeployment). Implies
    // deploymentFinished is set to the same instant; deploymentFailed stays false.
    @Column(name = "replaced_at")
    var replacedAt: Instant? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    val createdBy: UUID,
)
