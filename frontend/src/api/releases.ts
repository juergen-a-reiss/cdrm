// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http, toQueryString } from './http'
import type {
  RedeployRequest,
  ReleaseHistoryEntry,
  ReleaseHistoryFilterParams,
  ReleaseHistoryGroupBy,
  ReleaseHistoryPageResponse,
  ReleaseHistorySummaryEntry,
  ReleaseRequest,
  ReleaseResponse,
} from './types'

export interface ReleaseHistoryPageParams extends ReleaseHistoryFilterParams {
  sort?: string
  page?: number
  size?: number
}

export interface ReleaseHistorySummaryParams extends ReleaseHistoryFilterParams {
  groupBy: ReleaseHistoryGroupBy
}

export const releasesApi = {
  list: (sort?: string) => http.get<ReleaseResponse[]>(`/releases${sort ? `?sort=${encodeURIComponent(sort)}` : ''}`),
  get: (id: string) => http.get<ReleaseResponse>(`/releases/${id}`),
  history: (id: string) => http.get<ReleaseHistoryEntry[]>(`/releases/${id}/history`),
  historyOverview: (params: ReleaseHistoryPageParams = {}) =>
    http.get<ReleaseHistoryPageResponse>(
      `/releases/history${toQueryString({
        sort: params.sort,
        page: params.page,
        size: params.size,
        productId: params.productIds,
        workloadId: params.workloadIds,
        stageId: params.stageIds,
        pipeline: params.pipelines,
        action: params.actions,
        monthsBack: params.monthsBack,
        search: params.search,
      })}`,
    ),
  historySummary: (params: ReleaseHistorySummaryParams) =>
    http.get<ReleaseHistorySummaryEntry[]>(
      `/releases/history/summary${toQueryString({
        groupBy: params.groupBy,
        productId: params.productIds,
        workloadId: params.workloadIds,
        stageId: params.stageIds,
        pipeline: params.pipelines,
        action: params.actions,
        monthsBack: params.monthsBack,
      })}`,
    ),
  create: (request: ReleaseRequest) => http.post<ReleaseResponse>('/releases', request),
  update: (id: string, request: ReleaseRequest) => http.put<ReleaseResponse>(`/releases/${id}`, request),
  promote: (id: string) => http.post<ReleaseResponse>(`/releases/${id}/promote`, undefined),
  rollback: (id: string) => http.post<ReleaseResponse>(`/releases/${id}/rollback`, undefined),
  redeploy: (id: string, request: RedeployRequest) => http.post<ReleaseResponse>(`/releases/${id}/redeploy`, request),
  remove: (id: string) => http.delete(`/releases/${id}`),
}
