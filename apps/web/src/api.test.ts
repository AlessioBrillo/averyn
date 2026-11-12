import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { ApiError, deleteActivity, getActivity, listActivities } from './api'

const token = vi.hoisted(() => ({ value: 'tok' as string | null }))
vi.mock('./auth', () => ({ currentToken: async () => token.value }))

const fetchMock = vi.fn()

beforeEach(() => {
  token.value = 'tok'
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

test('a request carries the bearer token and returns the parsed body', async () => {
  fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null }))
  expect(await listActivities()).toEqual({ items: [], nextCursor: null })
  expect(fetchMock).toHaveBeenCalledWith('/v1/activities', {
    method: 'GET',
    headers: { Authorization: 'Bearer tok' },
    body: undefined,
  })
})

test('the cursor is encoded into the query', async () => {
  fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null }))
  await listActivities('a b&c')
  expect(fetchMock.mock.calls[0][0]).toBe('/v1/activities?cursor=a%20b%26c')
})

test('without a token it is a 401 and the network is never called', async () => {
  token.value = null
  await expect(listActivities()).rejects.toMatchObject({ status: 401 })
  expect(fetchMock).not.toHaveBeenCalled()
})

test('a 401 and a 403 from the server keep their status and code', async () => {
  fetchMock.mockResolvedValueOnce(json(401, { error: 'unauthorized' }))
  await expect(getActivity('x')).rejects.toMatchObject({ status: 401, code: 'unauthorized' })
  fetchMock.mockResolvedValueOnce(json(403, { error: 'account_deleted', message: 'gone' }))
  const error = await getActivity('x').catch((e: unknown) => e)
  expect(error).toBeInstanceOf(ApiError)
  expect(error).toMatchObject({ status: 403, code: 'account_deleted', detail: 'gone' })
})

test('an error body that is not JSON still gives the status', async () => {
  fetchMock.mockResolvedValue(new Response('<html>bad gateway</html>', { status: 502 }))
  await expect(getActivity('x')).rejects.toMatchObject({ status: 502, code: null, detail: null })
})

test('a network failure is not turned into an ApiError', async () => {
  fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
  await expect(getActivity('x')).rejects.toBeInstanceOf(TypeError)
})

test('the id is encoded in the path and a delete answers nothing', async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
  await deleteActivity('a/b')
  expect(fetchMock.mock.calls[0][0]).toBe('/v1/activities/a%2Fb')
  expect(fetchMock.mock.calls[0][1].method).toBe('DELETE')
})
