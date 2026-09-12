// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

// Elapsed time from `since` to now, as a compact string ("3d 4h", "45m") — used for a
// live workload's uptime. Coarsest-first, two units at most, since anything more
// precise isn't meaningful for "how long has this been running".
export function formatUptime(since: string | null): string {
  if (!since) return '—'
  const elapsedMs = Date.now() - new Date(since).getTime()
  if (elapsedMs < 0) return '—'

  const minutes = Math.floor(elapsedMs / 60_000)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)

  if (days > 0) return `${days}d ${hours % 24}h`
  if (hours > 0) return `${hours}h ${minutes % 60}m`
  if (minutes > 0) return `${minutes}m`
  return '<1m'
}
