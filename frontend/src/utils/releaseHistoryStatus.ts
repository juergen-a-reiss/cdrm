// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import type { GitOpsStatus, KubernetesStatus, ReleaseHistoryEntry, ReleaseHistoryOverviewEntry } from '../api/types'
import { formatDateTime } from './formatDateTime'

// Colors match docs/deployment-status-state-machine.odg: green only for an outright
// success, yellow for anything still retrying/in-progress, orange for the ambiguous
// "superseded" outcome, red for a terminal failure.
const GITOPS_STATUS_DISPLAY: Record<GitOpsStatus, { label: string; color: string } | null> = {
  NOT_APPLICABLE: null,
  PENDING: { label: 'Pending', color: 'grey' },
  PUSH_SUCCEEDED: { label: 'Push succeeded', color: 'success' },
  PUSH_FAILED_RETRYING: { label: 'Push failed, retrying', color: 'warning' },
  PUSH_FAILED: { label: 'Push failed', color: 'error' },
}

const KUBERNETES_STATUS_DISPLAY: Record<KubernetesStatus, { label: string; color: string } | null> = {
  NOT_APPLICABLE: null,
  NOT_STARTED: { label: 'Not started', color: 'grey' },
  AWAITING_CLUSTER_SYNC: { label: 'Awaiting cluster sync', color: 'warning' },
  ROLLING_OUT: { label: 'Rolling out', color: 'warning' },
  HEALTHY: { label: 'Healthy', color: 'success' },
  FAILED: { label: 'Failed', color: 'error' },
  REPLACED: { label: 'Replaced', color: 'orange' },
}

// null means "hide this column for this row" (NOT_APPLICABLE) — per the design doc,
// that's a stage/namespace that isn't GitOps-managed, or a non-Kubernetes workload.
export function gitOpsStatusDisplay(status: GitOpsStatus): { label: string; color: string } | null {
  return GITOPS_STATUS_DISPLAY[status]
}

export function kubernetesStatusDisplay(status: KubernetesStatus): { label: string; color: string } | null {
  return KUBERNETES_STATUS_DISPLAY[status]
}

export function formatDeploymentStatus(entry: ReleaseHistoryEntry | ReleaseHistoryOverviewEntry): string {
  // Rollout verification concluded the deploy didn't actually succeed — takes priority
  // over everything else, since deployedAt is still set for a row that reached this
  // state (the image patch itself was accepted).
  if (entry.deploymentFailed) return `Failed (${entry.deployError})`
  // The patch was accepted but a Kubernetes rollout hasn't been confirmed complete yet.
  if (entry.deployedAt && !entry.deploymentFinished) return `Verifying rollout… (${formatDateTime(entry.deployedAt)})`
  if (entry.deployedAt) return formatDateTime(entry.deployedAt)
  // A real attempt failure (e.g. cluster unreachable) is more informative than a
  // scheduled trigger time, so it takes priority when both happen to be present (a
  // SCHEDULED-policy row that came due and then failed on retry).
  if (entry.deployError) return `Pending (${entry.deployError})`
  if (entry.scheduledAt) return `Pending (${formatDateTime(entry.scheduledAt)})`
  return 'Pending'
}
