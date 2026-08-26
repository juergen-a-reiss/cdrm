// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { useIdFilter } from './useIdFilter'

export function useStageFilter() {
  const { selectedIds, clear, matches } = useIdFilter('cdrm.stageFilter')
  return { selectedStageIds: selectedIds, clear, matches }
}
