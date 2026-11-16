// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { useIdFilter } from './useIdFilter'

export function useStageFilter() {
  const { selectedIds, clear, matches } = useIdFilter('cdrm.stageFilter')
  return { selectedStageIds: selectedIds, clear, matches }
}
