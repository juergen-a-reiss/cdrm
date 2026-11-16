// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { http } from './http'
import type { StageRequest, StageResponse } from './types'

export const stagesApi = {
  list: (sort?: string) => http.get<StageResponse[]>(`/stages${sort ? `?sort=${encodeURIComponent(sort)}` : ''}`),
  get: (id: string) => http.get<StageResponse>(`/stages/${id}`),
  create: (request: StageRequest) => http.post<StageResponse>('/stages', request),
  update: (id: string, request: StageRequest) => http.put<StageResponse>(`/stages/${id}`, request),
  remove: (id: string) => http.delete(`/stages/${id}`),
}
