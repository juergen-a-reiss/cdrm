// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'
import type { ReleaseHistoryEntry, ReleaseRequest, ReleaseResponse } from './types'

export const releasesApi = {
  list: () => http.get<ReleaseResponse[]>('/releases'),
  get: (id: string) => http.get<ReleaseResponse>(`/releases/${id}`),
  history: (id: string) => http.get<ReleaseHistoryEntry[]>(`/releases/${id}/history`),
  create: (request: ReleaseRequest) => http.post<ReleaseResponse>('/releases', request),
  update: (id: string, request: ReleaseRequest) => http.put<ReleaseResponse>(`/releases/${id}`, request),
  promote: (id: string) => http.post<ReleaseResponse>(`/releases/${id}/promote`, undefined),
  remove: (id: string) => http.delete(`/releases/${id}`),
}
