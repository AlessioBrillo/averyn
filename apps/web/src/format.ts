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
