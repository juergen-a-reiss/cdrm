// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { useIdFilter } from './useIdFilter'

export function useWorkloadFilter() {
  const { selectedIds, clear, matches } = useIdFilter('cdrm.workloadFilter')
  return { selectedWorkloadIds: selectedIds, clear, matches }
}
