import { useState } from 'react'
import { ApiError, importGpx } from './api'
import type { ImportResponse } from './api'

const SPORTS = ['RUN', 'RIDE', 'WALK', 'HIKE'] as const

/** The same limit the server enforces (TDD-0002 §4): the file is checked here to fail fast, not to be sure. */
const MAX_BYTES = 32 * 1024 * 1024

/** What the user is told for each error the server can answer (TDD-0008 §5.5). */
export function messageFor(error: ApiError): string {
  switch (error.code) {
    case 'invalid_time':
      return 'The file has a track point without a usable time. A GPX track needs a time on every point.'
    case 'invalid_coordinate':
      return 'The file has a track point that is not on the globe.'
    case 'invalid_elevation':
      return 'The file has a track point with an unreadable elevation.'
    case 'no_track':
      return 'The file has no track points. Only recorded tracks can be imported.'
    case 'xml_error':
      return 'The file is not readable XML. A GPX file is an XML file.'
    case 'non_monotonic_segments':
      return 'A track segment of the file starts before the previous one ends.'
    case 'too_many_points':
      return 'The file has too many track points to import.'
    case 'invalid_sport':
      return 'Choose the sport of the activity.'
    case 'unsupported_media_type':
      return 'Choose a GPX file.'
    default:
      break
  }
  if (error.status === 413) return 'The file is too large. The limit is 32 MB.'
  if (error.status === 415) return 'Choose a GPX file.'
  if (error.status === 429 || error.status === 503) return 'The server is busy. Try again in a moment.'
  if (error.status === 507) return 'There is no storage left. Delete an activity and try again.'
  if (error.status === 401) return 'The session expired. Sign in again.'
  return 'The file could not be imported.'
}

/**
 * The import page (TDD-0008): a GPX file goes in, an activity comes out. The browser only uploads and shows
 * what the server computed: it never parses the file and never computes a metric.
 */
export function ImportGpx({
  onUnauthorized,
  onImported,
}: {
  onUnauthorized: () => void
  /** Called with the activity that was created, or that already existed. */
  onImported: (id: string, duplicate: boolean) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [sport, setSport] = useState<string>('')
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [result, setResult] = useState<ImportResponse | null>(null)

  function choose(selected: File | null) {
    setFile(selected)
    setFailed(null)
    setResult(null)
  }

  function submit() {
    if (!file || !sport) {
      setFailed('Choose a file and the sport of the activity.')
      return
    }
    if (file.size > MAX_BYTES) {
      setFailed('The file is too large. The limit is 32 MB.')
      return
    }
    setBusy(true)
    setFailed(null)
    importGpx(file, sport).then(
      (response) => {
        setBusy(false)
        setResult(response)
        onImported(response.activity.id, response.import.duplicate)
      },
      (e: unknown) => {
        setBusy(false)
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else setFailed(e instanceof ApiError ? messageFor(e) : 'The file could not be imported.')
      },
    )
  }

  const ignored = result?.import.ignoredExtensions ?? {}

  return (
    <>
      <h2>Import a GPX file</h2>
      <p>
        The file is uploaded as it is and kept next to the activity. The numbers are computed by the server, from
        the track it reads.
      </p>
      <p>
        <label>
          File{' '}
          <input
            type="file"
            accept=".gpx,application/gpx+xml,application/xml"
            onChange={(e) => choose(e.target.files?.[0] ?? null)}
          />
        </label>
      </p>
      <p>
        <label>
          Sport{' '}
          <select value={sport} onChange={(e) => setSport(e.target.value)}>
            <option value="">Choose…</option>
            {SPORTS.map((s) => (
              <option key={s} value={s}>
                {s.toLowerCase()}
              </option>
            ))}
          </select>
        </label>
      </p>
      <p>
        <button onClick={submit} disabled={busy}>
          {busy ? 'Importing…' : 'Import'}
        </button>
      </p>
      {failed && <p role="alert">{failed}</p>}
      {result && (
        <p role="status">
          {result.import.duplicate
            ? 'This file was already imported: opening the activity that exists.'
            : 'Imported.'}
          {result.import.segments !== null && ` ${result.import.points} points in ${result.import.segments} segments.`}
          {Object.keys(ignored).length > 0 &&
            ` Not imported: ${Object.keys(ignored).join(', ')} (the original file is kept).`}
        </p>
      )}
    </>
  )
}
