// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

import { useIdFilter } from './useIdFilter'

// Pipelines aren't their own entity — a pipeline is just the set of stages sharing a
// `pipeline` value — so there's no id to filter by. useIdFilter still works: the
// pipeline name itself is used as the "id".
export function usePipelineFilter() {
  const { selectedIds, clear, matches } = useIdFilter('cdrm.pipelineFilter')
  return { selectedPipelines: selectedIds, clear, matches }
}
