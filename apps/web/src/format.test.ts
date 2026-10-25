import { afterEach, expect, test } from 'vitest'
import {
  formatDistance,
  formatDuration,
  formatElevation,
  formatElevationDelta,
  formatPace,
  formatQuality,
  formatRate,
  formatSpeed,
  formatTargetDistance,
  setUnits,
} from './format'

afterEach(() => setUnits('metric'))

test('distance in km with two decimals', () => {
  expect(formatDistance(0)).toBe('0.00 km')
  expect(formatDistance(1000)).toBe('1.00 km')
  expect(formatDistance(10_234)).toBe('10.23 km')
})

test('duration as h:mm:ss', () => {
  expect(formatDuration(0)).toBe('0:00:00')
  expect(formatDuration(59_999)).toBe('0:00:59')
  expect(formatDuration(3 * 3_600_000 + 5_000)).toBe('3:00:05')
})

test('pace rounds to whole seconds without showing 60', () => {
  expect(formatPace(300)).toBe('5:00 /km')
  expect(formatPace(359.6)).toBe('6:00 /km')
  expect(formatPace(null)).toBe('—')
})

test('speed in km/h', () => {
  expect(formatSpeed(10)).toBe('36.0 km/h')
})

test('rides show speed, foot sports show pace', () => {
  const rate = { averageSpeedMps: 10, paceSecPerKm: 100 }
  expect(formatRate('RIDE', rate)).toEqual({ label: 'Average speed', value: '36.0 km/h' })
  expect(formatRate('RUN', rate)).toEqual({ label: 'Average pace', value: '1:40 /km' })
  expect(formatRate('HIKE', { averageSpeedMps: 0, paceSecPerKm: null }).value).toBe('—')
})

test('an unmeasured quality says so instead of showing a bare word', () => {
  expect(formatQuality('GOOD')).toBe('good')
  expect(formatQuality('POOR')).toBe('poor')
  expect(formatQuality('UNKNOWN')).toContain('not measured')
})

test('imperial units: miles, min/mi, mph and feet', () => {
  setUnits('imperial')
  expect(formatDistance(1609.344)).toBe('1.00 mi')
  expect(formatDistance(10_000)).toBe('6.21 mi')
  expect(formatPace(300)).toBe('8:03 /mi')
  expect(formatSpeed(10)).toBe('22.4 mph')
  expect(formatElevation(100)).toBe('328 ft')
  expect(formatElevationDelta(-30.48)).toBe('−100 ft')
  expect(formatRate('RUN', { averageSpeedMps: 3, paceSecPerKm: 300 }).value).toBe('8:03 /mi')
  expect(formatRate('RIDE', { averageSpeedMps: 10, paceSecPerKm: null }).value).toBe('22.4 mph')
})

test('the distance of a record keeps its name whatever the units', () => {
  setUnits('imperial')
  expect(formatTargetDistance(5000)).toBe('5 km')
})

test('the units are metric until the profile says otherwise', () => {
  expect(formatDistance(1000)).toBe('1.00 km')
  expect(formatElevation(100)).toBe('100 m')
})
