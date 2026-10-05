import { expect, test } from 'vitest'
import { formatDistance, formatDuration, formatPace, formatRate, formatSpeed } from './format'

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
