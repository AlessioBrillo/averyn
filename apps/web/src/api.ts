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

/** An authenticated GET. A missing or expired token is a 401 like the server's: the UI offers sign-in again. */
async function get<T>(path: string): Promise<T> {
  const token = await currentToken()
  if (!token) throw new ApiError(401)
  const response = await fetch(path, { headers: { Authorization: `Bearer ${token}` } })
  if (!response.ok) throw new ApiError(response.status)
  return (await response.json()) as T
}

export function listActivities(cursor?: string): Promise<ActivityPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
  return get<ActivityPage>(`/v1/activities${query}`)
}

export const getActivity = (id: string) => get<ActivitySummary>(`/v1/activities/${encodeURIComponent(id)}`)

export const getTrack = (id: string) => get<Track>(`/v1/activities/${encodeURIComponent(id)}/track`)
