// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'
import type { WorkloadRequest, WorkloadResponse } from './types'

export const workloadsApi = {
  list: () => http.get<WorkloadResponse[]>('/workloads'),
  get: (id: string) => http.get<WorkloadResponse>(`/workloads/${id}`),
  create: (request: WorkloadRequest) => http.post<WorkloadResponse>('/workloads', request),
  update: (id: string, request: WorkloadRequest) => http.put<WorkloadResponse>(`/workloads/${id}`, request),
  remove: (id: string) => http.delete(`/workloads/${id}`),
}
