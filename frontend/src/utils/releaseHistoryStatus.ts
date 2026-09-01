// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import type { ReleaseHistoryEntry, ReleaseHistoryOverviewEntry } from '../api/types'
import { formatDateTime } from './formatDateTime'

export function formatDeploymentStatus(entry: ReleaseHistoryEntry | ReleaseHistoryOverviewEntry): string {
  if (entry.deployedAt) return formatDateTime(entry.deployedAt)
  // A real attempt failure (e.g. cluster unreachable) is more informative than a
  // scheduled trigger time, so it takes priority when both happen to be present (a
  // SCHEDULED-policy row that came due and then failed on retry).
  if (entry.deployError) return `Pending (${entry.deployError})`
  if (entry.scheduledAt) return `Pending (${formatDateTime(entry.scheduledAt)})`
  return 'Pending'
}
