// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { useDateTimePreferences } from '../composables/useDateTimePreferences'

// Longest tokens first so e.g. "YYYY" isn't partially matched as "YY" — see the format
// field's hint text in UserProfileMenu for the user-facing list of these.
const TOKEN_PATTERN = /YYYY|YY|MM|DD|HH|hh|mm|ss|SSS|TZ/g

// The calendar fields of `date` as they read in `timeZone` (undefined = the browser's
// own) — Intl.DateTimeFormat.formatToParts is what makes this possible without pulling
// in a date library: it resolves every field for an arbitrary IANA zone directly.
function partsInZone(date: Date, timeZone: string | undefined) {
  const formatted = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZoneName: 'short',
  }).formatToParts(date)
  const part = (type: string) => formatted.find((p) => p.type === type)?.value ?? ''
  // Some locales render midnight as "24" rather than "00" even with hour12:false.
  const hour = part('hour') === '24' ? '00' : part('hour')
  return {
    year: part('year'),
    month: part('month'),
    day: part('day'),
    hour,
    minute: part('minute'),
    second: part('second'),
    tz: part('timeZoneName'),
  }
}

function formatWithPattern(date: Date, pattern: string, timeZone: string | undefined): string {
  const p = partsInZone(date, timeZone)
  const hour12 = String(((Number(p.hour) + 11) % 12) + 1).padStart(2, '0')
  const tokens: Record<string, string> = {
    YYYY: p.year,
    YY: p.year.slice(-2),
    MM: p.month,
    DD: p.day,
    HH: p.hour,
    hh: hour12,
    mm: p.minute,
    ss: p.second,
    SSS: String(date.getMilliseconds()).padStart(3, '0'),
    TZ: p.tz,
  }
  return pattern.replace(TOKEN_PATTERN, (token) => tokens[token])
}

// Every rendered timestamp in the app goes through here, reactively honoring the
// viewer's own timezone/format choice — see UserProfileMenu's "Date & Time Display"
// section for how that's set, and useDateTimePreferences for how it's stored.
export function formatDateTime(value: string): string {
  const prefs = useDateTimePreferences().value
  const date = new Date(value)
  const timeZone = prefs.timezoneMode === 'custom' ? prefs.timezone : undefined
  if (prefs.formatMode === 'browser') {
    return timeZone ? date.toLocaleString(undefined, { timeZone }) : date.toLocaleString()
  }
  return formatWithPattern(date, prefs.format, timeZone)
}
