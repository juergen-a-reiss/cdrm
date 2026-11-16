// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

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
