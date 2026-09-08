// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http, toQueryString } from './http'
import type { AuditFilterParams, AuditPageResponse } from './types'

export interface AuditPageParams extends AuditFilterParams {
  sort?: string
  page?: number
  size?: number
}

export const auditApi = {
  findAll: (params: AuditPageParams = {}) =>
    http.get<AuditPageResponse>(
      `/audit${toQueryString({
        sort: params.sort,
        page: params.page,
        size: params.size,
        entityType: params.entityTypes,
        action: params.actions,
        search: params.search,
      })}`,
    ),
}
