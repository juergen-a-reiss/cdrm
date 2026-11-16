// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { http } from './http'
import type { ClusterRequest, ClusterResponse } from './types'

export const clustersApi = {
  list: (sort?: string) => http.get<ClusterResponse[]>(`/clusters${sort ? `?sort=${encodeURIComponent(sort)}` : ''}`),
  get: (id: string) => http.get<ClusterResponse>(`/clusters/${id}`),
  create: (request: ClusterRequest) => http.post<ClusterResponse>('/clusters', request),
  update: (id: string, request: ClusterRequest) => http.put<ClusterResponse>(`/clusters/${id}`, request),
  remove: (id: string) => http.delete(`/clusters/${id}`),
}
