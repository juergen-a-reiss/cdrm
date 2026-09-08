// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http, toQueryString } from './http'

export const usersApi = {
  // Resolves each id to a ready-to-show string per the current user.id.storage display
  // format — formatting happens entirely server-side (see UserDisplayService), so this
  // is the only thing the frontend ever needs to call.
  displayNames: (ids: string[]) => http.get<Record<string, string>>(`/users/display-names${toQueryString({ id: ids })}`),
}
