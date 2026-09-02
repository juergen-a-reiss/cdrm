// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { useIdFilter } from './useIdFilter'

// Pipelines aren't their own entity — a pipeline is just the set of stages sharing a
// `pipeline` value — so there's no id to filter by. useIdFilter still works: the
// pipeline name itself is used as the "id".
export function usePipelineFilter() {
  const { selectedIds, clear, matches } = useIdFilter('cdrm.pipelineFilter')
  return { selectedPipelines: selectedIds, clear, matches }
}
