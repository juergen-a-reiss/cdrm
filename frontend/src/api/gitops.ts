// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'

export const gitOpsApi = {
  // Just the configured repo URLs (never credentials) — for the cluster-editing
  // screen's dropdown, so a cluster's/namespace's gitRepo can only ever be one of these.
  repositories: () => http.get<string[]>('/gitops/repositories'),
}
