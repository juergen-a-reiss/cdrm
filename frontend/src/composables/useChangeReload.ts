// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { onUnmounted } from 'vue'
import { onChange, type ChangeMessage } from './useChangeSocket'

// Reloads whenever a WebSocket change message's `type` starts with the given CloudEvents
// prefix (e.g. "dev.juergenreiss.cdrm.cluster.") — the same pattern ReleasesView/
// ReleaseHistoryDashboardView already use inline for release-history, pulled out here
// since every plain full-list view (Clusters/Stages/Products/Workloads/Configuration)
// needs it identically: none of those five sort/filter/paginate server-side, so a change
// anywhere in the list just means "refetch the whole thing", same as reloading after the
// view's own create/update/delete already does.
export function useChangeReload(prefix: string, reload: () => void) {
  const unsubscribe = onChange((message: ChangeMessage) => {
    if (message.type.startsWith(prefix)) reload()
  })
  onUnmounted(unsubscribe)
}
