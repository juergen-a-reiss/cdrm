// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { http } from './http'
import type { GitOpsTemplateTestRequest, GitOpsTemplateTestResponse } from './types'

export const gitOpsApi = {
  // Just the configured repo URLs (never credentials) — for the cluster-editing
  // screen's dropdown, so a cluster's/namespace's gitRepo can only ever be one of these.
  repositories: () => http.get<string[]>('/gitops/repositories'),
  // Runs a TEMPLATE-mode namespace's script server-side against a chosen (workload,
  // stage) pair, without writing or pushing anything — see the "Test" button in
  // ClusterFormDialog.
  testTemplate: (request: GitOpsTemplateTestRequest) =>
    http.post<GitOpsTemplateTestResponse>('/gitops/templates/test', request),
}
