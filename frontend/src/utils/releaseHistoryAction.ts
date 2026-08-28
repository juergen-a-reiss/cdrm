// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import type { ReleaseHistoryAction } from '../api/types'
import { CATEGORICAL_PALETTE } from './categoricalPalette'

export const RELEASE_HISTORY_ACTION_LABELS: Record<ReleaseHistoryAction, string> = {
  CREATED: 'Created',
  PROMOTED: 'Promoted',
  ROLLED_BACK: 'Rolled back',
  REDEPLOYED: 'Redeployed',
}

// Fixed order, not cycled — a stable slot per action everywhere it appears
// (chart, legend, table), unlike the hash-derived colors used for open-ended
// entity sets like products/workloads.
export const RELEASE_HISTORY_ACTIONS: ReleaseHistoryAction[] = ['PROMOTED', 'ROLLED_BACK', 'REDEPLOYED', 'CREATED']

export const RELEASE_HISTORY_ACTION_COLORS: Record<ReleaseHistoryAction, string> = {
  PROMOTED: CATEGORICAL_PALETTE[0],
  ROLLED_BACK: CATEGORICAL_PALETTE[1],
  REDEPLOYED: CATEGORICAL_PALETTE[2],
  CREATED: CATEGORICAL_PALETTE[3],
}
