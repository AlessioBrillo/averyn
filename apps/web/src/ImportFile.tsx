import { useState } from 'react'
import { ApiError, importArchive, importFile } from './api'
import type { ArchiveReport, ImportResponse } from './api'
import { formatSport } from './format'
import { t } from './i18n'
import { importKind, maxBytesFor, messageFor, entryReason, sportRequired, statusLabel } from './importRules'

const SPORTS = ['RUN', 'RIDE', 'WALK', 'HIKE'] as const

/**
 * The import page (TDD-0008, TDD-0013): a GPX file, a FIT file or an archive of them goes in, activities come out.
 * The browser only uploads and shows what the server computed: it never parses a file and never computes a metric.
 */
export function ImportFile({
  onUnauthorized,
  onImported,
}: {
  onUnauthorized: () => void
  /** Called with the activity that was created; a duplicate stays on the page. */
  onImported: (id: string) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [sport, setSport] = useState<string>('')
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [result, setResult] = useState<ImportResponse | null>(null)
  const [archive, setArchive] = useState<ArchiveReport | null>(null)

  const kind = file ? importKind(file.name) : null

  function choose(selected: File | null) {
    setFile(selected)
    setFailed(null)
    setResult(null)
    setArchive(null)
  }

  function fail(e: unknown) {
    setBusy(false)
    if (e instanceof ApiError && e.status === 401) onUnauthorized()
    else setFailed(e instanceof ApiError ? messageFor(e) : t('The file could not be imported.'))
  }

  function submit() {
    if (!file) {
      setFailed(t('Choose a file.'))
      return
    }
    if (!kind) {
      setFailed(t('Choose a GPX file, a FIT file or a ZIP archive.'))
      return
    }
    if (sportRequired(kind) && !sport) {
      setFailed(t('Choose the sport of the activity.'))
      return
    }
    if (file.size > maxBytesFor(kind)) {
      setFailed(t('The file is too large. The limit is {mb} MB.', { mb: maxBytesFor(kind) / (1024 * 1024) }))
      return
    }
    setBusy(true)
    setFailed(null)
    if (kind === 'zip') {
      importArchive(file, sport || null).then((report) => {
        setBusy(false)
        setArchive(report)
      }, fail)
      return
    }
    importFile(file, kind, sport || null).then((response) => {
      setBusy(false)
      setResult(response)
      // A new import opens the activity; a duplicate stays here and says so, with a link to the activity
      // that already exists.
      if (!response.import.duplicate) onImported(response.activity.id)
    }, fail)
  }

  const ignored = result?.import.ignoredExtensions ?? {}

  return (
    <>
      <h2>{t('Import activities')}</h2>
      <p>
        {t(
          'A GPX file, a FIT file from a watch or a bike computer, or a ZIP archive of them. The file is uploaded as it is and kept next to the activity. The numbers are computed by the server, from the track it reads.',
        )}
      </p>
      <p>
        <label>
          {t('File')}{' '}
          <input
            type="file"
            accept=".gpx,.fit,.zip,application/gpx+xml,application/zip"
            onChange={(e) => choose(e.target.files?.[0] ?? null)}
          />
        </label>
      </p>
      <p>
        <label>
          {t('Sport')}{' '}
          <select value={sport} onChange={(e) => setSport(e.target.value)}>
            <option value="">{kind && !sportRequired(kind) ? t('From the file') : t('Choose…')}</option>
            {SPORTS.map((s) => (
              <option key={s} value={s}>
                {formatSport(s)}
              </option>
            ))}
          </select>
        </label>
        {kind === 'fit' && ` ${t('A FIT file says its own sport; choose one only to override it.')}`}
        {kind === 'zip' && ` ${t('Used for the files that do not say a sport (every GPX file).')}`}
      </p>
      <p>
        <button onClick={submit} disabled={busy}>
          {busy ? t('Importing…') : t('Import')}
        </button>
      </p>
      {failed && <p role="alert">{failed}</p>}
      {result && (
        <p role="status">
          {result.import.duplicate ? (
            <>
              {t('This file was already imported.')}{' '}
              <a href={`/activities/${result.activity.id}`}>{t('Open the activity that exists')}</a>.
            </>
          ) : (
            t('Imported.')
          )}
          {result.import.segments !== null &&
            ` ${t('{points} points in {segments} segments.', { points: result.import.points ?? 0, segments: result.import.segments })}`}
          {result.import.heartRateReadings
            ? ` ${t('{n} heart-rate readings.', { n: result.import.heartRateReadings })}`
            : ''}
          {Object.keys(ignored).length > 0 &&
            ` ${t('Not imported: {names} (the original file is kept).', { names: Object.keys(ignored).join(', ') })}`}
        </p>
      )}
      {archive && <ArchiveResult report={archive} />}
    </>
  )
}

/** The report of an archive: the totals and one line for each file. */
export function ArchiveResult({ report }: { report: ArchiveReport }) {
  return (
    <section role="status" aria-labelledby="archive-heading">
      <h3 id="archive-heading">{t('The archive')}</h3>
      <p>
        {t('{created} imported, {duplicates} already imported, {refused} refused', {
          created: report.created,
          duplicates: report.duplicates,
          refused: report.refused,
        })}
        {report.skipped > 0 && `, ${t('{n} not tried', { n: report.skipped })}`}
        {report.ignoredFiles > 0 && `. ${t('{n} other files were not read', { n: report.ignoredFiles })}`}.
      </p>
      {report.entries.length > 0 && (
        <div className="table-scroll">
          <table>
            <caption className="visually-hidden">{t('What happened to each file of the archive')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('File')}</th>
                <th scope="col">{t('Result')}</th>
              </tr>
            </thead>
            <tbody>
              {report.entries.map((entry) => (
                <tr key={entry.name}>
                  <td>{entry.name}</td>
                  <td>
                    {entry.status === 'created' && entry.activityId ? (
                      <a href={`/activities/${entry.activityId}`}>{t('imported')}</a>
                    ) : entry.status === 'duplicate' && entry.activityId ? (
                      <a href={`/activities/${entry.activityId}`}>{t('already imported')}</a>
                    ) : (
                      `${statusLabel(entry.status)}${entry.code ? `: ${entryReason(entry.code)}` : ''}`
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
