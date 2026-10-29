// Display only: every metric is computed by the backend (TDD-0003 W2), these just turn numbers into text.

import { t } from './i18n'

export type Units = 'metric' | 'imperial'

const METERS_PER_MILE = 1609.344
const METERS_PER_FOOT = 0.3048

// The unit system of the signed-in user (TDD-0012). One value for the whole page, set when the profile is
// loaded or saved; the app remounts its content when it changes, so no formatter needs the unit passed in.
// ponytail: module state, a context if a second consumer ever needs to react without a remount.
let units: Units = 'metric'

export function setUnits(next: Units): void {
  units = next
}

export const distanceUnit = (): string => (units === 'imperial' ? 'mi' : 'km')
export const elevationUnit = (): string => (units === 'imperial' ? 'ft' : 'm')

/** A distance in metres, in the unit of the user (km or mi), as a number. */
export const metersToDistanceUnit = (meters: number): number =>
  units === 'imperial' ? meters / METERS_PER_MILE : meters / 1000

/** An altitude or a climb in metres, in the unit of the user (m or ft), as a number. */
export const metersToElevationUnit = (meters: number): number =>
  units === 'imperial' ? meters / METERS_PER_FOOT : meters

export function formatDistance(meters: number): string {
  return `${metersToDistanceUnit(meters).toFixed(2)} ${distanceUnit()}`
}

/** h:mm:ss, truncated to whole seconds. */
export function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const ss = String(total % 60).padStart(2, '0')
  return `${h}:${mm}:${ss}`
}

/** m:ss per km (or per mile); an em dash when there is no pace (nothing moved). */
export function formatPace(secPerKm: number | null): string {
  if (secPerKm === null) return '—'
  const total = Math.round(units === 'imperial' ? (secPerKm * METERS_PER_MILE) / 1000 : secPerKm)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')} /${distanceUnit()}`
}

export function formatSpeed(metersPerSecond: number): string {
  return units === 'imperial'
    ? `${((metersPerSecond * 3600) / METERS_PER_MILE).toFixed(1)} mph`
    : `${(metersPerSecond * 3.6).toFixed(1)} km/h`
}

/** Pace for foot sports, speed for rides. */
export function formatRate(
  sport: string,
  rate: { averageSpeedMps: number; paceSecPerKm: number | null },
): { label: string; value: string } {
  return sport === 'RIDE'
    ? { label: t('Average speed'), value: formatSpeed(rate.averageSpeedMps) }
    : { label: t('Average pace'), value: formatPace(rate.paceSecPerKm) }
}

/**
 * The quality grade as it is shown. `UNKNOWN` is not a bad grade: it means the accuracy was never measured
 * (an imported file carries none), and the app says so instead of showing a bare word (TDD-0008).
 */
export function formatQuality(grade: string): string {
  switch (grade) {
    case 'GOOD':
      return t('good')
    case 'FAIR':
      return t('fair')
    case 'POOR':
      return t('poor')
    case 'UNKNOWN':
      return t('unknown (accuracy not measured)')
    default:
      return grade.toLowerCase()
  }
}

/** A sport as it is shown: the name of the sport in the language of the page. */
export function formatSport(sport: string): string {
  switch (sport) {
    case 'RUN':
      return t('run')
    case 'RIDE':
      return t('ride')
    case 'WALK':
      return t('walk')
    case 'HIKE':
      return t('hike')
    default:
      return sport.toLowerCase()
  }
}

/** An altitude, in metres or feet. */
export function formatElevation(meters: number): string {
  return `${Math.round(metersToElevationUnit(meters))} ${elevationUnit()}`
}

/** A climb, with its sign: "+120 m", "-35 m". */
export function formatElevationDelta(meters: number): string {
  const sign = meters > 0 ? '+' : meters < 0 ? '−' : ''
  return `${sign}${Math.round(metersToElevationUnit(Math.abs(meters)))} ${elevationUnit()}`
}

/** A share, in percent: "98%". */
export function formatCoverage(percent: number): string {
  return `${Math.round(percent)}%`
}

/** A heart rate, in beats per minute. */
export function formatHeartRate(bpm: number): string {
  return `${Math.round(bpm)} bpm`
}

/**
 * A target distance of a record: "1 km", "21.1 km". The distances of the records are the standard ones of
 * the sport, so they are shown as they are and not rounded to two decimals like a measurement. They stay in
 * kilometres whatever the unit system: "the 5 km record" is a name, not a measurement (TDD-0012).
 */
export function formatTargetDistance(meters: number): string {
  const km = meters / 1000
  return `${Number.isInteger(km) ? km : km.toFixed(1)} km`
}
