// Display only: every metric is computed by the backend (TDD-0003 W2), these just turn numbers into text.

export function formatDistance(meters: number): string {
  return `${(meters / 1000).toFixed(2)} km`
}

/** h:mm:ss, truncated to whole seconds. */
export function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const ss = String(total % 60).padStart(2, '0')
  return `${h}:${mm}:${ss}`
}

/** m:ss per km; an em dash when there is no pace (nothing moved). */
export function formatPace(secPerKm: number | null): string {
  if (secPerKm === null) return '—'
  const total = Math.round(secPerKm)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')} /km`
}

export function formatSpeed(metersPerSecond: number): string {
  return `${(metersPerSecond * 3.6).toFixed(1)} km/h`
}

/** Pace for foot sports, speed for rides. */
export function formatRate(
  sport: string,
  rate: { averageSpeedMps: number; paceSecPerKm: number | null },
): { label: string; value: string } {
  return sport === 'RIDE'
    ? { label: 'Average speed', value: formatSpeed(rate.averageSpeedMps) }
    : { label: 'Average pace', value: formatPace(rate.paceSecPerKm) }
}

/**
 * The quality grade as it is shown. `UNKNOWN` is not a bad grade: it means the accuracy was never measured
 * (an imported file carries none), and the app says so instead of showing a bare word (TDD-0008).
 */
export function formatQuality(grade: string): string {
  return grade === 'UNKNOWN' ? 'unknown (accuracy not measured)' : grade.toLowerCase()
}

/** An altitude, in metres. */
export function formatElevation(meters: number): string {
  return `${Math.round(meters)} m`
}

/** A climb, with its sign: "+120 m", "-35 m". */
export function formatElevationDelta(meters: number): string {
  const sign = meters > 0 ? '+' : meters < 0 ? '−' : ''
  return `${sign}${Math.round(Math.abs(meters))} m`
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
 * the sport, so they are shown as they are and not rounded to two decimals like a measurement.
 */
export function formatTargetDistance(meters: number): string {
  const km = meters / 1000
  return `${Number.isInteger(km) ? km : km.toFixed(1)} km`
}
