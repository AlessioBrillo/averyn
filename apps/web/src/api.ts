import { currentToken } from './auth'

export interface ClientConfig {
  issuer: string
  clientId: string
  webClientId: string
  mapStyleUrl: string
}

export interface ActivitySummary {
  id: string
  clientActivityId: string
  sport: 'RUN' | 'RIDE' | 'WALK' | 'HIKE'
  startedAt: string
  finalState: 'COMPLETED' | 'FAILED'
  distanceM: number
  elapsedMs: number
  movingMs: number
  pausedMs: number
  averageSpeedMps: number
  paceSecPerKm: number | null
  qualityGrade: 'GOOD' | 'FAIR' | 'POOR'
  droppedRecords: number
  metricsAlgorithmVersion: string
  qualityAlgorithmVersion: string
  visibility: string
}

export interface ActivityPage {
  items: ActivitySummary[]
  nextCursor: string | null
}

/** A GeoJSON Feature; the geometry is null when the activity has no usable track (W6). */
export interface Track {
  type: 'Feature'
  properties: Record<string, unknown> | null
  geometry: { type: 'LineString'; coordinates: [number, number][] } | null
}

export class ApiError extends Error {
  status: number
  constructor(status: number) {
    super(`HTTP ${status}`)
    this.status = status
  }
}

/** An authenticated request. A missing or expired token is a 401 like the server's: the UI offers sign-in again. */
async function request(path: string, method = 'GET'): Promise<Response> {
  const token = await currentToken()
  if (!token) throw new ApiError(401)
  const response = await fetch(path, { method, headers: { Authorization: `Bearer ${token}` } })
  if (!response.ok) throw new ApiError(response.status)
  return response
}

async function get<T>(path: string): Promise<T> {
  return (await (await request(path)).json()) as T
}

/** Removes the activity and its raw file for good (ADR-0017). */
export const deleteActivity = async (id: string) => {
  await request(`/v1/activities/${encodeURIComponent(id)}`, 'DELETE')
}

/** Removes everything Averyn holds for the user; the identity at the identity provider stays (ADR-0017). */
export const deleteAccount = async () => {
  await request('/v1/me', 'DELETE')
}

/** Everything the user has stored, as a ZIP (TDD-0004). A plain link cannot carry the bearer token, hence a fetch. */
export const downloadExport = async () => (await request('/v1/me/export')).blob()

export function listActivities(cursor?: string): Promise<ActivityPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
  return get<ActivityPage>(`/v1/activities${query}`)
}

export const getActivity = (id: string) => get<ActivitySummary>(`/v1/activities/${encodeURIComponent(id)}`)

export const getTrack = (id: string) => get<Track>(`/v1/activities/${encodeURIComponent(id)}/track`)
