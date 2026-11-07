import type { SynapseFeatures, SynapseWindow } from './api'
import type { ChartSeries } from './analysisSeries'

/** The fields of the feature frames that are drawn as a line. */
export type FeatureField = 'heartRateBpm' | 'ppgQuality' | 'motionArtifact' | 'motionLevel'

/**
 * A feature field over time: the minutes since the recording began and the value, null where the band marked it
 * "not available" and across every gap. A line drawn from the last frame before a hole to the first after it would
 * invent a signal that was never measured.
 */
export function featureSeries(features: SynapseFeatures, field: FeatureField): ChartSeries {
  const x: number[] = []
  const y: (number | null)[] = []
  const values = features[field]
  features.elapsedMs.forEach((elapsed, i) => {
    const previous = i > 0 ? features.elapsedMs[i - 1] : undefined
    if (previous !== undefined && features.gaps.some((g) => g.tMs >= previous && g.tMs < elapsed)) {
      x.push((previous + elapsed) / 2 / 60_000)
      y.push(null)
    }
    x.push(elapsed / 60_000)
    y.push(values[i] ?? null)
  })
  return { x, y }
}

/** A band for a chart: seconds since the recording began, and the lowest and highest value of each bucket. */
export interface Band {
  x: number[]
  min: (number | null)[]
  max: (number | null)[]
}

/**
 * One channel of a signal window as a band. Between two segments there is a gap and the band breaks there (a null
 * point at the end of the segment before): the signal is never joined across a hole. Where a bucket is a single sample
 * the minimum and the maximum are the same value, so the band is the line itself.
 */
export function windowBand(window: SynapseWindow, channel: number): Band {
  const x: number[] = []
  const min: (number | null)[] = []
  const max: (number | null)[] = []
  for (const segment of window.segments) {
    const lows = segment.min[channel]
    const highs = segment.max[channel]
    if (!lows || !highs || lows.length === 0) continue
    lows.forEach((low, i) => {
      x.push((segment.t0Ms + (i + 0.5) * segment.bucketMs) / 1000)
      min.push(low)
      max.push(highs[i] ?? low)
    })
    x.push((segment.t0Ms + lows.length * segment.bucketMs) / 1000)
    min.push(null)
    max.push(null)
  }
  return { x, min, max }
}

/** The part of the recording on screen, in milliseconds since it began. */
export interface Range {
  fromMs: number
  toMs: number
}

const MIN_SPAN_MS = 20

/** Zoom by [factor] (below 1 zooms in) around the middle of the range, inside the recording. */
export function zoomRange(range: Range, totalMs: number, factor: number): Range {
  const span = Math.min(totalMs, Math.max(MIN_SPAN_MS, (range.toMs - range.fromMs) * factor))
  const middle = (range.fromMs + range.toMs) / 2
  return clampRange({ fromMs: middle - span / 2, toMs: middle + span / 2 }, totalMs)
}

/** Move by a share of the span (negative goes back in time), staying inside the recording. */
export function panRange(range: Range, totalMs: number, share: number): Range {
  const shift = (range.toMs - range.fromMs) * share
  return clampRange({ fromMs: range.fromMs + shift, toMs: range.toMs + shift }, totalMs)
}

function clampRange(range: Range, totalMs: number): Range {
  const span = range.toMs - range.fromMs
  const from = Math.min(Math.max(0, range.fromMs), Math.max(0, totalMs - span))
  return { fromMs: Math.round(from), toMs: Math.round(from + span) }
}
