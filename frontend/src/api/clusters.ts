// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'
import type { ClusterRequest, ClusterResponse } from './types'

export const clustersApi = {
  list: (sort?: string) => http.get<ClusterResponse[]>(`/clusters${sort ? `?sort=${encodeURIComponent(sort)}` : ''}`),
  get: (id: string) => http.get<ClusterResponse>(`/clusters/${id}`),
  create: (request: ClusterRequest) => http.post<ClusterResponse>('/clusters', request),
  update: (id: string, request: ClusterRequest) => http.put<ClusterResponse>(`/clusters/${id}`, request),
  remove: (id: string) => http.delete(`/clusters/${id}`),
}
