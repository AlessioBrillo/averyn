import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import { Analysis } from './Analysis'
import { elevationSeries, heartRateSeries } from './analysisSeries'
import type { ActivityAnalysis } from './api'

const analysis: ActivityAnalysis = {
  schemaVersion: 2,
  algorithmVersions: {
    splits: 'splits-v1',
    elevation: 'elevation-v1',
    series: 'series-v1',
    heartRate: 'hr-v2',
    bestEfforts: 'best-efforts-v1',
  },
  splitLengthM: 1000,
  totals: {
    distanceM: 1500,
    elapsedMs: 600_000,
    movingMs: 540_000,
    pausedMs: 60_000,
    stationaryMs: 0,
  },
  elevation: {
    source: 'recorded',
    gainM: 120,
    lossM: 15,
    minM: 98,
    maxM: 220,
    avgM: 150,
    coveragePercent: 100,
  },
  heartRate: {
    minBpm: 110,
    avgBpm: 132,
    maxBpm: 168,
    coveragePercent: 97.5,
    dropouts: 15,
    artifacts: 0,
  },
  splits: [
    {
      index: 1,
      startDistanceM: 0,
      endDistanceM: 1000,
      elapsedMs: 400_000,
      movingMs: 360_000,
      avgSpeedMps: 2.78,
      elevationGainM: 100,
      elevationLossM: 10,
      avgHrBpm: 130,
      partial: false,
    },
    {
      index: 2,
      startDistanceM: 1000,
      endDistanceM: 1500,
      elapsedMs: 200_000,
      movingMs: 180_000,
      avgSpeedMps: 2.78,
      elevationGainM: 20,
      elevationLossM: 5,
      avgHrBpm: 135,
      partial: true,
    },
  ],
  bestEfforts: [
    {
      distanceM: 1000,
      durationMs: 360_000,
      elapsedMs: 362_000,
      startDistanceM: 0,
      endDistanceM: 1000,
      startElapsedMs: 0,
      endElapsedMs: 360_000,
      avgSpeedMps: 2.78,
      paceSecPerKm: 360,
    },
  ],
  series: {
    distanceM: [0, 500, 1000, 1500],
    elevationM: [100, 101, 102, 103],
    elapsedMs: [0, 200_000, 400_000, 600_000],
    segment: [0, 0, 1, 1],
    heartRate: {
      elapsedMs: [0, 60_000, 120_000, 180_000],
      bpm: [120, 125, 130, 128],
      segment: [0, 0, 1, 1],
    },
  },
}

test('the elevation profile breaks where the activity was paused', () => {
  const series = elevationSeries(analysis)

  expect(series.x).toEqual([0, 0.5, 1, 1.5])
  // The first point of the second recording interval is a hole: the profile does not cross the pause.
  expect(series.y).toEqual([100, 101, null, 103])
})

test('an altitude that was not measured is a hole and not a line', () => {
  const withoutAltitude = {
    ...analysis,
    series: { ...analysis.series, elevationM: [100, null, 102, 103] },
  }

  expect(elevationSeries(withoutAltitude).y).toEqual([100, null, null, 103])
})

test('the heart rate breaks where the sensor was silent', () => {
  const series = heartRateSeries(analysis)

  expect(series.x).toEqual([0, 1, 2, 3])
  expect(series.y).toEqual([120, 125, null, 128])
})

test('the analysis shows the elevation, the heart rate and the splits', () => {
  const html = renderToString(createElement(Analysis, { analysis, sport: 'RUN' }))

  expect(html).toContain('Analysis')
  expect(html).toContain('Elevation gain')
  expect(html).toContain('+120 m')
  expect(html).toContain('Heart rate')
  expect(html).toContain('132 bpm avg')
  expect(html).toContain('Splits')
  expect(html).toContain('1.00 km')
  expect(html).toContain('(partial)')
  // A run is measured in pace, not in speed.
  expect(html).toContain('Pace')
})

test('a ride shows the speed of the splits and not the pace', () => {
  const html = renderToString(createElement(Analysis, { analysis, sport: 'RIDE' }))

  expect(html).toContain('Speed')
  expect(html).toContain('10.0 km/h')
})

test('the analysis shows the best efforts of the activity', () => {
  const html = renderToString(createElement(Analysis, { analysis, sport: 'RUN' }))

  expect(html).toContain('Best efforts')
  expect(html).toContain('1 km')
  expect(html).toContain('0:06:00')
  expect(html).toContain('6:00 /km')
  expect(html).toContain('best-efforts-v1')
})
