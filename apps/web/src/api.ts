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
  /** The time the activity was recording but the user was not moving. */
  stationaryMs: number
  averageSpeedMps: number
  paceSecPerKm: number | null
  /** `UNKNOWN` when the activity has no measured accuracy (an imported file, TDD-0008). */
  qualityGrade: 'GOOD' | 'FAIR' | 'POOR' | 'UNKNOWN'
  droppedRecords: number
  metricsAlgorithmVersion: string
  qualityAlgorithmVersion: string
  visibility: string
  avgHrBpm: number
  maxHrBpm: number
  hrSource: string
  /** The algorithm that computed the heart rate. */
  hrAlgorithmVersion: string
  /** `device` (recorded) or `gpx` (imported, TDD-0008). */
  source: string
}

/** Which algorithm produced which number of the analysis (TDD-0009). */
export interface AnalysisVersions {
  splits: string
  elevation: string
  series: string
  heartRate: string
}

export interface AnalysisTotals {
  distanceM: number
  elapsedMs: number
  movingMs: number
  pausedMs: number
  stationaryMs: number
}

/** The elevation of the activity; null when the activity has no altitude at all. */
export interface AnalysisElevation {
  source: string
  gainM: number
  lossM: number
  minM: number
  maxM: number
  avgM: number
  coveragePercent: number
}

/** The heart rate of the activity; null when there was no heart rate. */
export interface AnalysisHeartRate {
  minBpm: number
  avgBpm: number
  maxBpm: number
  coveragePercent: number
  dropouts: number
  artifacts: number
}

/** One automatic split. */
export interface AnalysisSplit {
  index: number
  startDistanceM: number
  endDistanceM: number
  elapsedMs: number
  movingMs: number
  avgSpeedMps: number
  elevationGainM: number | null
  elevationLossM: number | null
  avgHrBpm: number | null
  partial: boolean
}

/** The display series: a bounded, decimated view of the track, the elevation and the heart rate. */
export interface AnalysisSeries {
  distanceM: number[]
  elevationM: (number | null)[]
  elapsedMs: number[]
  /** The recording interval of each point: a change of number is a pause. */
  segment: number[]
  heartRate: {
    elapsedMs: number[]
    bpm: number[]
    /** The stretch of samples of each point: a change of number is a dropout of the sensor. */
    segment: number[]
  }
}

/** Everything the analysis of one activity holds (TDD-0009). */
export interface ActivityAnalysis {
  schemaVersion: number
  algorithmVersions: AnalysisVersions
  splitLengthM: number
  totals: AnalysisTotals
  elevation: AnalysisElevation | null
  heartRate: AnalysisHeartRate | null
  splits: AnalysisSplit[]
  series: AnalysisSeries
}

export interface ActivityPage {
  items: ActivitySummary[]
  nextCursor: string | null
}

/** A GeoJSON Feature; the geometry is null when the activity has no usable track (W6). */
export interface Track {
  type: 'Feature'
  properties: Record<string, unknown> | null
  /** One line per continuous recording interval: a pause ends a line instead of being bridged. */
  geometry: { type: 'MultiLineString'; coordinates: [number, number][][] } | null
}

/** What the importer read out of the file (TDD-0008). */
export interface ImportReport {
  source: string
  duplicate: boolean
  segments: number | null
  points: number | null
  ignoredExtensions: Record<string, number>
  parserVersion: string
}

export interface ImportResponse {
  activity: ActivitySummary
  import: ImportReport
}

export class ApiError extends Error {
  status: number
  /** The stable code the server sent (`invalid_time`, `invalid_sport`, …), when there is one. */
  code: string | null
  /** The server's message, when there is one. */
  detail: string | null
  constructor(status: number, code: string | null = null, detail: string | null = null) {
    super(detail ?? `HTTP ${status}`)
    this.status = status
    this.code = code
    this.detail = detail
  }
}

/** An authenticated request. A missing or expired token is a 401 like the server's: the UI offers sign-in again. */
async function request(
  path: string,
  method = 'GET',
  body?: BodyInit,
  contentType?: string,
): Promise<Response> {
  const token = await currentToken()
  if (!token) throw new ApiError(401)
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
  if (contentType) headers['Content-Type'] = contentType
  const response = await fetch(path, { method, headers, body })
  if (!response.ok) throw await toApiError(response)
  return response
}

/** The error the server sent, with its code: the UI shows a different message for each. */
async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as { error?: string; message?: string }
    return new ApiError(response.status, body.error ?? null, body.message ?? null)
  } catch {
    return new ApiError(response.status)
  }
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

/**
 * The analysis of the activity: the automatic splits, the elevation and the series (TDD-0009). The server
 * computes it when it is missing, so the first read of an old activity can take a moment.
 */
export const getAnalysis = (id: string) =>
  get<ActivityAnalysis>(`/v1/activities/${encodeURIComponent(id)}/analysis`)

/**
 * Imports a GPX file as an activity (TDD-0008). The file is sent as it is; the server parses it, keeps it and
 * computes the numbers. The same file imported twice answers with the activity that already exists.
 */
export const importGpx = async (file: File, sport: string): Promise<ImportResponse> => {
  const response = await request(
    `/v1/imports/gpx?sport=${encodeURIComponent(sport)}`,
    'POST',
    file,
    'application/gpx+xml',
  )
  return (await response.json()) as ImportResponse
}

export interface ConsentGrant {
  scope: string
  grantedAt: string
  revokedAt: string | null
}

/** The user's consent grants (ADR-0021). */
export const getConsents = () => get<ConsentGrant[]>('/v1/consents')

/** Revoke a consent scope (ADR-0021). */
export const revokeConsent = (scope: string) =>
  request(`/v1/consents/${encodeURIComponent(scope)}`, 'DELETE')

/** Grant a consent scope (ADR-0021): without it the device never records the data it covers. */
export const grantConsent = (scope: string) =>
  request(`/v1/consents/${encodeURIComponent(scope)}`, 'POST')
