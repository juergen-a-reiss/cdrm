// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'
import type { StageRequest, StageResponse } from './types'

export const stagesApi = {
  list: (sort?: string) => http.get<StageResponse[]>(`/stages${sort ? `?sort=${encodeURIComponent(sort)}` : ''}`),
  get: (id: string) => http.get<StageResponse>(`/stages/${id}`),
  create: (request: StageRequest) => http.post<StageResponse>('/stages', request),
  update: (id: string, request: StageRequest) => http.put<StageResponse>(`/stages/${id}`, request),
  remove: (id: string) => http.delete(`/stages/${id}`),
}
