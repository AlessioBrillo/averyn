import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import { ApiError } from './api'
import type { ArchiveReport } from './api'
import { ArchiveResult, ImportFile } from './ImportFile'
import { entryReason, importKind, maxBytesFor, messageFor, sportRequired } from './importRules'

test('the import page asks for a file and a sport', () => {
  const html = renderToString(createElement(ImportFile, { onUnauthorized: () => {}, onImported: () => {} }))

  expect(html).toContain('Import activities')
  expect(html).toContain('type="file"')
  expect(html).toContain('accept=".gpx,.fit,.zip')
  expect(html).toContain('Sport')
  expect(html).toContain('RUN')
})

test('a file is a track, a watch file or an archive by its name, and nothing else is read', () => {
  expect(importKind('morning.GPX')).toBe('gpx')
  expect(importKind('2026-10-26-run.fit')).toBe('fit')
  expect(importKind('export_123.zip')).toBe('zip')
  expect(importKind('photo.jpg')).toBeNull()
  expect(importKind('run.fit.bak')).toBeNull()
})

test('only a GPX file needs the sport, and an archive may be bigger than a file', () => {
  expect(sportRequired('gpx')).toBe(true)
  expect(sportRequired('fit')).toBe(false)
  expect(sportRequired('zip')).toBe(false)
  expect(maxBytesFor('fit')).toBe(32 * 1024 * 1024)
  expect(maxBytesFor('zip')).toBe(256 * 1024 * 1024)
})

test('every error the server can answer has its own message', () => {
  expect(messageFor(new ApiError(422, 'invalid_time'))).toContain('time')
  expect(messageFor(new ApiError(422, 'no_track'))).toContain('no track points')
  expect(messageFor(new ApiError(422, 'xml_error'))).toContain('XML')
  expect(messageFor(new ApiError(422, 'fit_crc'))).toContain('damaged')
  expect(messageFor(new ApiError(422, 'fit_not_activity'))).toContain('not an activity')
  expect(messageFor(new ApiError(422, 'sport_unknown'))).toContain('Choose one')
  expect(messageFor(new ApiError(422, 'archive_too_large'))).toContain('100')
  expect(messageFor(new ApiError(422, 'zip_error'))).toContain('ZIP')
  expect(messageFor(new ApiError(413))).toContain('too large')
  expect(messageFor(new ApiError(507))).toContain('storage')
  expect(messageFor(new ApiError(503))).toContain('busy')
})

test('the reason a file of an archive was refused is in words', () => {
  expect(entryReason('entry_too_large')).toContain('too large')
  expect(entryReason('fit_crc')).toContain('damaged')
  expect(entryReason(null)).toBe('')
})

test('the report of an archive has the totals and a line for each file', () => {
  const report: ArchiveReport = {
    created: 2,
    duplicates: 1,
    refused: 1,
    skipped: 0,
    ignoredFiles: 3,
    entries: [
      { name: 'a/run.fit', status: 'created', activityId: 'id-1', code: null, message: null },
      { name: 'a/copy.fit', status: 'duplicate', activityId: 'id-1', code: null, message: null },
      { name: 'a/broken.fit', status: 'refused', activityId: null, code: 'fit_crc', message: null },
    ],
  }

  const html = renderToString(createElement(ArchiveResult, { report }))

  expect(html).toContain('2 imported, 1 already imported, 1 refused')
  expect(html).toContain('3 other files were not read')
  expect(html).toContain('href="/activities/id-1"')
  expect(html).toContain('a/broken.fit')
  expect(html).toContain('damaged')
})
