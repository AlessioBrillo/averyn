// The rules of the import page that do not need a browser: what a file is and what it needs (TDD-0013).

import { ApiError } from './api'
import type { ImportKind } from './api'

/** The same limits the server enforces: the file is checked here to fail fast, not to be sure. */
export const MAX_FILE_BYTES = 32 * 1024 * 1024
export const MAX_ARCHIVE_BYTES = 256 * 1024 * 1024

/** What a file is, from its name; null for anything the import does not read. */
export function importKind(name: string): ImportKind | null {
  const lower = name.toLowerCase()
  if (lower.endsWith('.gpx')) return 'gpx'
  if (lower.endsWith('.fit')) return 'fit'
  if (lower.endsWith('.zip')) return 'zip'
  return null
}

/** A GPX file has no sport the server can trust; a FIT file declares one; an archive has both. */
export function sportRequired(kind: ImportKind): boolean {
  return kind === 'gpx'
}

export function maxBytesFor(kind: ImportKind): number {
  return kind === 'zip' ? MAX_ARCHIVE_BYTES : MAX_FILE_BYTES
}

/** What the user is told for each error the server can answer (TDD-0008 §5.5, TDD-0013). */
export function messageFor(error: ApiError): string {
  switch (error.code) {
    case 'invalid_time':
      return 'The file has a point without a usable time. Every point of a track needs one.'
    case 'invalid_coordinate':
      return 'The file has a point that is not on the globe.'
    case 'invalid_elevation':
      return 'The file has a point with an unreadable elevation.'
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
    case 'sport_unknown':
      return 'The file does not say a sport Averyn records. Choose one.'
    case 'not_fit':
      return 'The file is not a FIT file.'
    case 'fit_crc':
      return 'The FIT file is damaged: its checksum does not match. Export it again.'
    case 'fit_truncated':
      return 'The FIT file is cut short. Export it again.'
    case 'fit_structure':
      return 'The FIT file is not valid.'
    case 'fit_not_activity':
      return 'The FIT file is not an activity (it is a course, a workout or settings).'
    case 'zip_error':
      return 'The file is not a readable ZIP archive.'
    case 'archive_too_large':
      return 'The archive is too large to import at once. Import it in smaller parts (at most 100 activity files).'
    case 'unsupported_media_type':
      return 'Choose a GPX file, a FIT file or a ZIP archive.'
    default:
      break
  }
  if (error.status === 413) return 'The file is too large.'
  if (error.status === 415) return 'Choose a GPX file, a FIT file or a ZIP archive.'
  if (error.status === 429 || error.status === 503) return 'The server is busy. Try again in a moment.'
  if (error.status === 507) return 'There is no storage left. Delete an activity and try again.'
  if (error.status === 401) return 'The session expired. Sign in again.'
  return 'The file could not be imported.'
}

/** The reason one file of an archive was refused, in words. */
export function entryReason(code: string | null): string {
  if (code === 'entry_too_large') return 'The file is too large.'
  if (code === 'entry_unreadable') return 'The file could not be read from the archive.'
  if (code === 'quota_exceeded') return 'There is no storage left.'
  if (code === null) return ''
  return messageFor(new ApiError(422, code))
}
