// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { usePersistedRef } from './usePersistedRef'

export type TimezoneMode = 'browser' | 'custom'
export type DateFormatMode = 'browser' | 'custom'

export interface DateTimePreferences {
  timezoneMode: TimezoneMode
  // Only meaningful when timezoneMode is 'custom' — an IANA timezone name (e.g. "UTC",
  // "Europe/Berlin"). Starts out as DEFAULT_CUSTOM_TIMEZONE (see UserProfileMenu, where
  // switching into custom mode for the first time shows it preselected), then remembers
  // whatever the user last chose.
  timezone: string
  formatMode: DateFormatMode
  // Only meaningful when formatMode is 'custom' — see utils/formatDateTime.ts for the
  // supported tokens.
  format: string
}

export const DEFAULT_CUSTOM_TIMEZONE = 'UTC'
export const DEFAULT_CUSTOM_FORMAT = 'YYYY-MM-DD HH:mm:ss TZ'

const DEFAULTS: DateTimePreferences = {
  timezoneMode: 'browser',
  timezone: DEFAULT_CUSTOM_TIMEZONE,
  formatMode: 'browser',
  format: DEFAULT_CUSTOM_FORMAT,
}

// Shared, persisted (see usePersistedRef) across the whole app — set once in
// UserProfileMenu's "Date & Time Display" section, read by every call to
// utils/formatDateTime.ts, wherever a timestamp is rendered.
export function useDateTimePreferences() {
  return usePersistedRef<DateTimePreferences>('cdrm.dateTimePreferences', DEFAULTS)
}

// Common on a corporate Windows fleet, or a Safari old enough to lack
// Intl.supportedValuesOf (< 17) — a short curated fallback so the picker still has
// something useful in it rather than failing outright.
const FALLBACK_TIMEZONES = [
  'UTC',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Paris',
  'Europe/Moscow',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'America/Sao_Paulo',
  'Asia/Tokyo',
  'Asia/Shanghai',
  'Asia/Kolkata',
  'Asia/Dubai',
  'Australia/Sydney',
  'Pacific/Auckland',
]

// Every IANA timezone name the browser knows about, for the picker.
export function supportedTimezones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
  try {
    const values = intl.supportedValuesOf?.('timeZone')
    if (values && values.length > 0) return values
  } catch {
    // fall through to the static list below
  }
  return FALLBACK_TIMEZONES
}
