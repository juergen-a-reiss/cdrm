// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import type { ReleaseHistoryEntry, ReleaseHistoryOverviewEntry } from '../api/types'
import { formatDateTime } from './formatDateTime'

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
