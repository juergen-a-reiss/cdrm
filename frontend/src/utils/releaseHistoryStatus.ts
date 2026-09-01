// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import type { ReleaseHistoryEntry, ReleaseHistoryOverviewEntry } from '../api/types'
import { formatDateTime } from './formatDateTime'

export function formatDeploymentStatus(entry: ReleaseHistoryEntry | ReleaseHistoryOverviewEntry): string {
  if (entry.deployedAt) return formatDateTime(entry.deployedAt)
  if (entry.scheduledAt) return `Pending (${formatDateTime(entry.scheduledAt)})`
  return 'Pending'
}
