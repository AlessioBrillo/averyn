import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import { RecordsView } from './Records'
import type { PersonalRecords } from './api'
import { formatTargetDistance } from './format'

const records: PersonalRecords = {
  algorithmVersion: 'best-efforts-v1',
  pendingActivities: 0,
  sports: [
    {
      sport: 'RUN',
      targets: [
        {
          distanceM: 1000,
          record: {
            activityId: '11111111-1111-1111-1111-111111111111',
            achievedAt: '2026-10-20T08:00:00Z',
            durationMs: 245_000,
            elapsedMs: 246_000,
            avgSpeedMps: 4.08,
            paceSecPerKm: 245,
            source: 'device',
            qualityGrade: 'GOOD',
          },
        },
        { distanceM: 5000, record: null },
      ],
    },
  ],
}

test('the records page shows the record of every distance', () => {
  const html = renderToString(createElement(RecordsView, { records, open: () => {} }))

  expect(html).toContain('Records')
  expect(html).toContain('1 km')
  expect(html).toContain('0:04:05')
  expect(html).toContain('4:05 /km')
  // A distance the account has not reached yet is there, without a record.
  expect(html).toContain('5 km')
  expect(html).toContain('run')
})

test('a record says where it comes from and how good the data was', () => {
  const html = renderToString(createElement(RecordsView, { records, open: () => {} }))

  expect(html).toContain('recorded')
  expect(html).toContain('good')
  expect(html).toContain('best-efforts-v1')
})

test('the page says when the records can be incomplete', () => {
  const html = renderToString(
    createElement(RecordsView, { records: { ...records, pendingActivities: 3 }, open: () => {} }),
  )

  expect(html).toContain('3')
  expect(html).toContain('incomplete')
})

test('an account without records says so', () => {
  const html = renderToString(
    createElement(RecordsView, { records: { ...records, sports: [] }, open: () => {} }),
  )

  expect(html).toContain('No record yet')
})

test('the distances of the records are shown as they are', () => {
  expect(formatTargetDistance(1000)).toBe('1 km')
  expect(formatTargetDistance(5000)).toBe('5 km')
  expect(formatTargetDistance(21097.5)).toBe('21.1 km')
  expect(formatTargetDistance(42195)).toBe('42.2 km')
  expect(formatTargetDistance(100000)).toBe('100 km')
})
