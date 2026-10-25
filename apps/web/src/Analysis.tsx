import { useEffect, useMemo, useRef } from 'react'
import type uPlot from 'uplot'
import type { ActivityAnalysis, HeartRateZones } from './api'
import { elevationSeries, heartRateSeries } from './analysisSeries'
import {
  distanceUnit,
  elevationUnit,
  formatCoverage,
  formatDistance,
  formatDuration,
  formatElevation,
  formatElevationDelta,
  formatHeartRate,
  formatPace,
  formatSpeed,
  formatTargetDistance,
} from './format'
import 'uplot/dist/uPlot.min.css'

/**
 * The time in each heart-rate zone, with a bar for the share. Without a zone result the reason is the profile:
 * the zones are cut from the maximum heart rate of the user (TDD-0012).
 */
export function HeartRateZonesTable({ zones }: { zones: HeartRateZones | null }) {
  if (zones === null) {
    return <p className="small">Set your maximum heart rate in your account to see the time in each zone.</p>
  }
  const total = zones.zones.reduce((sum, z) => sum + z.timeMs, 0)
  return (
    <div className="table-scroll">
      <table>
        <caption>{`Time in each zone, for a maximum heart rate of ${formatHeartRate(zones.maxHrBpm)}`}</caption>
        <thead>
          <tr>
            <th scope="col">Zone</th>
            <th scope="col">Heart rate</th>
            <th scope="col">Time</th>
            <th scope="col">Share</th>
          </tr>
        </thead>
        <tbody>
          {zones.zones.map((z) => {
            const share = total > 0 ? (z.timeMs / total) * 100 : 0
            return (
              <tr key={z.zone}>
                <th scope="row">Z{z.zone}</th>
                <td>{z.toBpm === null ? `${z.fromBpm}+ bpm` : `${z.fromBpm}–${z.toBpm} bpm`}</td>
                <td>{formatDuration(z.timeMs)}</td>
                <td>
                  <meter min={0} max={100} value={share} aria-label={`Zone ${z.zone}`} /> {formatCoverage(share)}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/** The colour of the track, used for the charts too. */
const ACCENT = '#e4572e'

const CHART_HEIGHT = 200

type UPlotConstructor = new (
  options: uPlot.Options,
  data?: uPlot.AlignedData,
  target?: HTMLElement,
) => uPlot

/** The options and the data of a chart, once the colours of the page are known. */
type Prepare = (colors: { text: string; grid: string }) => {
  data: uPlot.AlignedData
  options: uPlot.Options
}

function axisOptions(
  colors: { text: string; grid: string },
  label: string,
): uPlot.Axis {
  return {
    stroke: colors.text,
    font: '12px system-ui',
    label,
    labelFont: '12px system-ui',
    grid: { stroke: colors.grid },
  }
}

/**
 * A chart. The library is loaded when the chart is drawn, not when the page is: the detail page of an activity
 * without a chart must not pay for it.
 */
function Chart({
  prepare,
  label,
}: {
  prepare: Prepare
  label: string
}) {
  const element = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let plot: uPlot | undefined
    let disposed = false
    void import('uplot').then((module) => {
      const UPlot = (module as unknown as { default: UPlotConstructor }).default
      if (disposed || !element.current) return
      const style = getComputedStyle(element.current)
      const { data, options } = prepare({ text: style.color, grid: 'rgba(128, 128, 128, 0.25)' })
      plot = new UPlot(
        { ...options, width: element.current.clientWidth || 600, height: CHART_HEIGHT },
        data,
        element.current,
      )
    })
    const observer = new ResizeObserver(() => {
      if (plot && element.current) plot.setSize({ width: element.current.clientWidth, height: CHART_HEIGHT })
    })
    if (element.current) observer.observe(element.current)
    return () => {
      disposed = true
      observer.disconnect()
      plot?.destroy()
    }
  }, [prepare])

  return <div className="chart" ref={element} role="img" aria-label={label} />
}

/** The pace of a split, or its speed: a ride is not measured in minutes per kilometer. */
function splitRate(
  sport: string,
  split: { avgSpeedMps: number },
): string {
  if (sport === 'RIDE') return formatSpeed(split.avgSpeedMps)
  return split.avgSpeedMps > 0 ? formatPace(1000 / split.avgSpeedMps) : '—'
}

/**
 * The analysis of the activity (TDD-0009): the elevation, the heart rate and the automatic splits. Every number
 * comes from the server: the browser only draws what it is given.
 */
export function Analysis({
  analysis,
  sport,
}: {
  analysis: ActivityAnalysis
  sport: string
}) {
  const elevation = analysis.elevation
  const heart = analysis.heartRate
  const hasElevation = elevation !== null && analysis.series.elevationM.some((value) => value !== null)
  const hasHeartRate = heart !== null && analysis.series.heartRate.bpm.length > 0

  const elevationPrepare = useMemo<Prepare>(
    () => (colors) => {
      const series = elevationSeries(analysis)
      return {
        data: [series.x, series.y],
        options: {
          width: 600,
          height: CHART_HEIGHT,
          legend: { show: false },
          cursor: { drag: { x: false, y: false } },
          axes: [axisOptions(colors, distanceUnit()), axisOptions(colors, elevationUnit())],
          series: [{ }, { spanGaps: false, stroke: ACCENT, width: 2, fill: 'rgba(228, 87, 46, 0.12)' }],
        },
      }
    },
    [analysis],
  )

  const heartRatePrepare = useMemo<Prepare>(
    () => (colors) => {
      const series = heartRateSeries(analysis)
      return {
        data: [series.x, series.y],
        options: {
          width: 600,
          height: CHART_HEIGHT,
          legend: { show: false },
          cursor: { drag: { x: false, y: false } },
          axes: [axisOptions(colors, 'min'), axisOptions(colors, 'bpm')],
          series: [{ }, { spanGaps: false, stroke: ACCENT, width: 2 }],
        },
      }
    },
    [analysis],
  )

  const metrics: [string, string][] = []
  if (elevation) {
    metrics.push(['Elevation gain', formatElevationDelta(elevation.gainM)])
    metrics.push(['Elevation loss', formatElevationDelta(-elevation.lossM)])
    metrics.push(['Altitude min / max', `${formatElevation(elevation.minM)} / ${formatElevation(elevation.maxM)}`])
    if (elevation.coveragePercent < 100) {
      metrics.push(['Altitude measured', formatCoverage(elevation.coveragePercent)])
    }
  }
  if (heart) {
    metrics.push([
      'Heart rate',
      `${formatHeartRate(heart.avgBpm)} avg · ${formatHeartRate(heart.maxBpm)} max`,
    ])
    metrics.push(['Heart rate coverage', formatCoverage(heart.coveragePercent)])
  }

  // The charts are a picture; the numbers they draw are also a sentence, for the screen readers.
  const elevationLabel = elevation
    ? `Elevation profile: from ${formatElevation(elevation.minM)} to ${formatElevation(elevation.maxM)} over ` +
      `${formatDistance(analysis.totals.distanceM)}, ${formatElevationDelta(elevation.gainM)} of climb.`
    : 'Elevation profile.'
  const heartLabel = heart
    ? `Heart rate: ${formatHeartRate(heart.avgBpm)} on average, ${formatHeartRate(heart.maxBpm)} at most, ` +
      `measured ${formatCoverage(heart.coveragePercent)} of the recording time.`
    : 'Heart rate over the time.'

  return (
    <section aria-labelledby="analysis-heading" className="analysis">
      <h3 id="analysis-heading">Analysis</h3>
      {metrics.length > 0 && (
        <dl className="metrics">
          {metrics.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
      {hasElevation && (
        <>
          <h4>Elevation</h4>
          <Chart prepare={elevationPrepare} label={elevationLabel} />
          <p className="small">Estimated from the altitude the samples carry, not from a digital elevation model.</p>
        </>
      )}
      {hasHeartRate && (
        <>
          <h4>Heart rate</h4>
          <Chart prepare={heartRatePrepare} label={heartLabel} />
          <HeartRateZonesTable zones={analysis.heartRateZones ?? null} />
        </>
      )}
      {analysis.splits.length > 0 && (
        <>
          <h4>
            Splits (every {formatDistance(analysis.splitLengthM)})
          </h4>
          <div className="table-scroll">
            <table>
              <caption className="visually-hidden">The automatic splits of the activity</caption>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">Distance</th>
                  <th scope="col">Time</th>
                  <th scope="col">{sport === 'RIDE' ? 'Speed' : 'Pace'}</th>
                  <th scope="col">Elevation</th>
                  <th scope="col">Heart rate</th>
                </tr>
              </thead>
              <tbody>
                {analysis.splits.map((split) => (
                  <tr key={split.index}>
                    <th scope="row">{split.index}</th>
                    <td>
                      {formatDistance(split.endDistanceM - split.startDistanceM)}
                      {split.partial ? ' (partial)' : ''}
                    </td>
                    <td>{formatDuration(split.movingMs)}</td>
                    <td>{splitRate(sport, split)}</td>
                    <td>
                      {split.elevationGainM === null
                        ? '—'
                        : formatElevationDelta(split.elevationGainM)}
                    </td>
                    <td>{split.avgHrBpm === null ? '—' : formatHeartRate(split.avgHrBpm)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="small">The time of a split is the time the user was moving.</p>
        </>
      )}
      {analysis.bestEfforts.length > 0 && (
        <>
          <h4>Best efforts</h4>
          <div className="table-scroll">
            <table>
              <caption className="visually-hidden">The fastest windows of the activity</caption>
              <thead>
                <tr>
                  <th scope="col">Distance</th>
                  <th scope="col">Time</th>
                  <th scope="col">{sport === 'RIDE' ? 'Speed' : 'Pace'}</th>
                  <th scope="col">From</th>
                </tr>
              </thead>
              <tbody>
                {analysis.bestEfforts.map((effort) => (
                  <tr key={effort.distanceM}>
                    <th scope="row">{formatTargetDistance(effort.distanceM)}</th>
                    <td>{formatDuration(effort.durationMs)}</td>
                    <td>
                      {effort.avgSpeedMps > 0
                        ? sport === 'RIDE'
                          ? formatSpeed(effort.avgSpeedMps)
                          : formatPace(effort.paceSecPerKm)
                        : '—'}
                    </td>
                    <td>{formatDistance(effort.startDistanceM)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="small">
            The fastest window of the activity for that distance. The time is the time the athlete was moving.
          </p>
        </>
      )}
      <p className="small">
        Algorithms: {analysis.algorithmVersions.splits}, {analysis.algorithmVersions.elevation},{' '}
        {analysis.algorithmVersions.series}, {analysis.algorithmVersions.heartRate},{' '}
        {analysis.algorithmVersions.bestEfforts}
      </p>
    </section>
  )
}
