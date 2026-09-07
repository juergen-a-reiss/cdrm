// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'
import type { ConfigResponse } from './types'

export const configApi = {
  get: (key: string) => http.get<ConfigResponse>(`/config/${key}`),
  update: (key: string, value: unknown) => http.put<ConfigResponse>(`/config/${key}`, { value }),
}

export const menuVisibilityApi = {
  // The current user's own effective menu keys, computed server-side from their roles —
  // not the raw per-role whitelist (that's configApi.get('menu-visibility'), devops-only).
  effective: () => http.get<string[]>('/menu-visibility'),
}
