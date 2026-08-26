// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

export function formatDateTime(value: string): string {
  return new Date(value).toLocaleString()
}
