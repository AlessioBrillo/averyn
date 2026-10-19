import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import { ApiError } from './api'
import { ImportGpx, messageFor } from './ImportGpx'

test('the import page asks for a file and a sport', () => {
  const html = renderToString(
    createElement(ImportGpx, { onUnauthorized: () => {}, onImported: () => {} }),
  )

  expect(html).toContain('Import a GPX file')
  expect(html).toContain('type="file"')
  expect(html).toContain('Sport')
  expect(html).toContain('RUN')
  expect(html).toContain('Import')
})

test('every error the server can answer has its own message', () => {
  expect(messageFor(new ApiError(422, 'invalid_time'))).toContain('time')
  expect(messageFor(new ApiError(422, 'no_track'))).toContain('no track points')
  expect(messageFor(new ApiError(422, 'xml_error'))).toContain('XML')
  expect(messageFor(new ApiError(413))).toContain('too large')
  expect(messageFor(new ApiError(507))).toContain('storage')
  expect(messageFor(new ApiError(503))).toContain('busy')
})
