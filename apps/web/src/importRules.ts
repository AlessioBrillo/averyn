// The rules of the import page that do not need a browser: what a file is and what it needs (TDD-0013).

import { ApiError } from './api'
import { t } from './i18n'
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
      return t('The file has a point without a usable time. Every point of a track needs one.')
    case 'invalid_coordinate':
      return t('The file has a point that is not on the globe.')
    case 'invalid_elevation':
      return t('The file has a point with an unreadable elevation.')
    case 'no_track':
      return t('The file has no track points. Only recorded tracks can be imported.')
    case 'xml_error':
      return t('The file is not readable XML. A GPX file is an XML file.')
    case 'non_monotonic_segments':
      return t('A track segment of the file starts before the previous one ends.')
    case 'too_many_points':
      return t('The file has too many track points to import.')
    case 'invalid_sport':
      return t('Choose the sport of the activity.')
    case 'sport_unknown':
      return t('The file does not say a sport Averyn records. Choose one.')
    case 'not_fit':
      return t('The file is not a FIT file.')
    case 'fit_crc':
      return t('The FIT file is damaged: its checksum does not match. Export it again.')
    case 'fit_truncated':
      return t('The FIT file is cut short. Export it again.')
    case 'fit_structure':
      return t('The FIT file is not valid.')
    case 'fit_not_activity':
      return t('The FIT file is not an activity (it is a course, a workout or settings).')
    case 'zip_error':
      return t('The file is not a readable ZIP archive.')
    case 'archive_too_large':
      return t('The archive is too large to import at once. Import it in smaller parts (at most 100 activity files).')
    case 'unsupported_media_type':
      return t('Choose a GPX file, a FIT file or a ZIP archive.')
    default:
      break
  }
  if (error.status === 413) return t('The file is too large.')
  if (error.status === 415) return t('Choose a GPX file, a FIT file or a ZIP archive.')
  if (error.status === 429 || error.status === 503) return t('The server is busy. Try again in a moment.')
  if (error.status === 507) return t('There is no storage left. Delete an activity and try again.')
  if (error.status === 401) return t('The session expired. Sign in again.')
  return t('The file could not be imported.')
}

/** The reason one file of an archive was refused, in words. */
export function entryReason(code: string | null): string {
  if (code === 'entry_too_large') return t('The file is too large.')
  if (code === 'entry_unreadable') return t('The file could not be read from the archive.')
  if (code === 'quota_exceeded') return t('There is no storage left.')
  if (code === null) return ''
  return messageFor(new ApiError(422, code))
}

/** What happened to one file of an archive, in words. */
export function statusLabel(status: string): string {
  switch (status) {
    case 'created':
      return t('created')
    case 'duplicate':
      return t('duplicate')
    case 'refused':
      return t('refused')
    case 'skipped':
      return t('skipped')
    default:
      return status
  }
}
