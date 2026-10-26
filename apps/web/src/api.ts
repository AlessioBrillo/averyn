import { currentToken } from './auth'

export interface ClientConfig {
  issuer: string
  clientId: string
  webClientId: string
  mapStyleUrl: string
  /** The version of the API contract the server speaks (TDD-0011). */
  apiVersion?: number
  /** The oldest app version the instance supports; null when there is no minimum. */
  minClientVersion?: string | null
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
  /** The best efforts of the activity (`best-efforts-v1`, TDD-0010). */
  bestEfforts: string
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

/** One best effort of an activity: the fastest window of the track of exactly this distance (TDD-0010). */
export interface BestEffort {
  distanceM: number
  /** The moving time of the effort: what a record is made of. */
  durationMs: number
  /** The wall time of the effort, the stops inside it included. */
  elapsedMs: number
  startDistanceM: number
  endDistanceM: number
  startElapsedMs: number
  endElapsedMs: number
  avgSpeedMps: number
  paceSecPerKm: number | null
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
    /** The time at each bpm (`series-v2`): timeMs[i] is the time at fromBpm + i bpm. */
    timeAtBpm?: { fromBpm: number; timeMs: number[] }
  }
}

/** The time in each of five heart-rate zones for the maximum heart rate of the profile (`hr-zones-v1`). */
export interface HeartRateZones {
  algorithmVersion: string
  maxHrBpm: number
  zones: { zone: number; fromBpm: number; toBpm: number | null; timeMs: number }[]
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
  /** The fastest window of each target distance of the sport (TDD-0010). */
  bestEfforts: BestEffort[]
  series: AnalysisSeries
  /** Absent without a heart rate or without a maximum heart rate in the profile. */
  heartRateZones?: HeartRateZones | null
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
  /** The heart-rate readings imported; null for a duplicate. */
  heartRateReadings?: number | null
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
/** What a file the user picks is: a track, an activity file of a watch, or an archive of them (TDD-0013). */
export type ImportKind = 'gpx' | 'fit' | 'zip'

const IMPORT_TYPES: Record<ImportKind, string> = {
  gpx: 'application/gpx+xml',
  fit: 'application/vnd.ant.fit',
  zip: 'application/zip',
}

/** Uploads a GPX or a FIT file. The sport is required for GPX; a FIT file declares its own, so it is optional. */
export const importFile = async (file: File, kind: 'gpx' | 'fit', sport: string | null): Promise<ImportResponse> => {
  const query = sport ? `?sport=${encodeURIComponent(sport)}` : ''
  const response = await request(`/v1/imports/${kind}${query}`, 'POST', file, IMPORT_TYPES[kind])
  return (await response.json()) as ImportResponse
}

/** What happened to one file of an archive. */
export interface ArchiveEntry {
  name: string
  status: 'created' | 'duplicate' | 'refused' | 'skipped'
  activityId: string | null
  code: string | null
  message: string | null
}

export interface ArchiveReport {
  created: number
  duplicates: number
  refused: number
  skipped: number
  ignoredFiles: number
  entries: ArchiveEntry[]
}

/** Uploads a ZIP archive; `sport` is only for the files that do not say one. */
export const importArchive = async (file: File, sport: string | null): Promise<ArchiveReport> => {
  const query = sport ? `?sport=${encodeURIComponent(sport)}` : ''
  const response = await request(`/v1/imports/archive${query}`, 'POST', file, IMPORT_TYPES.zip)
  return (await response.json()) as ArchiveReport
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

/** The settings every client of the account shares (TDD-0012). */
export interface UserProfile {
  units: 'metric' | 'imperial'
  maxHrBpm: number | null
}

export const getProfile = () => get<UserProfile>('/v1/profile')

/** Replaces the profile; the server answers with the stored one. A 422 means it was not valid. */
export const putProfile = async (profile: UserProfile): Promise<UserProfile> =>
  (await (await request('/v1/profile', 'PUT', JSON.stringify(profile), 'application/json')).json()) as UserProfile

/** The fastest effort of a distance, and the activity that holds it (TDD-0010). */
export interface PersonalRecord {
  activityId: string
  /** When it was made: the start of the activity. */
  achievedAt: string
  durationMs: number
  elapsedMs: number
  avgSpeedMps: number
  paceSecPerKm: number | null
  /** `device` (recorded) or `gpx` (imported). */
  source: string
  /** `UNKNOWN` when the accuracy of the activity was never measured. */
  qualityGrade: string
}

/** One target distance of a sport, with the record of it when there is one. */
export interface RecordTarget {
  distanceM: number
  record: PersonalRecord | null
}

/** The records of one sport. */
export interface SportRecords {
  sport: string
  targets: RecordTarget[]
}

/** The personal records of the account. */
export interface PersonalRecords {
  /** The version of the algorithm that produced the numbers. */
  algorithmVersion: string
  /** The activities that have no analysis yet: the records can be incomplete. */
  pendingActivities: number
  sports: SportRecords[]
}

/** The personal records of the account (TDD-0010). */
export const getRecords = () => get<PersonalRecords>('/v1/me/records')
