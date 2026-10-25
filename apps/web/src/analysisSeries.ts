import type { ActivityAnalysis } from './api'
import { metersToDistanceUnit, metersToElevationUnit } from './format'

/** The points of a chart: the value on the x axis and the value on the y one, null where the line breaks. */
export interface ChartSeries {
  x: number[]
  y: (number | null)[]
}

/**
 * The elevation profile: the distance and the altitude, in the units of the user (km and m, or mi and ft). The line breaks where the activity was
 * paused and where the altitude is not known: a profile that crosses a pause would be the profile of a walk that
 * never happened.
 */
export function elevationSeries(analysis: ActivityAnalysis): ChartSeries {
  const x: number[] = []
  const y: (number | null)[] = []
  analysis.series.distanceM.forEach((distance, index) => {
    x.push(metersToDistanceUnit(distance))
    const segment = analysis.series.segment[index] ?? 0
    const previous = index > 0 ? (analysis.series.segment[index - 1] ?? segment) : segment
    const elevation = analysis.series.elevationM[index]
    y.push(segment !== previous || elevation === null || elevation === undefined ? null : metersToElevationUnit(elevation))
  })
  return { x, y }
}

/**
 * The heart rate over time: the minutes from the start and the beats per minute. The line breaks where the
 * sensor was silent: the heart rate was not measured there, and a straight line would invent it.
 */
export function heartRateSeries(analysis: ActivityAnalysis): ChartSeries {
  const { elapsedMs, bpm, segment } = analysis.series.heartRate
  const x: number[] = []
  const y: (number | null)[] = []
  elapsedMs.forEach((elapsed, index) => {
    x.push(elapsed / 60_000)
    const current = segment[index] ?? 0
    const previous = index > 0 ? (segment[index - 1] ?? current) : current
    y.push(current !== previous ? null : (bpm[index] ?? null))
  })
  return { x, y }
}
