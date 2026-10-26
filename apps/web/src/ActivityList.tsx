import { useCallback, useEffect, useState } from 'react'
import { ApiError, listActivities } from './api'
import type { ActivitySummary } from './api'
import { formatDistance, formatDuration, formatQuality, formatRate, formatSport } from './format'
import { t } from './i18n'

export function ActivityList({
  open,
  onUnauthorized,
  onImport,
}: {
  open: (id: string) => void
  /** Must be stable (useCallback): it is an effect dependency. */
  onUnauthorized: () => void
  /** Goes to the import page (TDD-0008). */
  onImport: () => void
}) {
  const [items, setItems] = useState<ActivitySummary[]>([])
  const [next, setNext] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [accountDeleted, setAccountDeleted] = useState(false)

  const fetchPage = useCallback(
    (cursor?: string) =>
      listActivities(cursor)
        .then((page) => {
          setItems((previous) => (cursor ? [...previous, ...page.items] : page.items))
          setNext(page.nextCursor)
        })
        .catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 401) onUnauthorized()
          else if (e instanceof ApiError && e.status === 403) setAccountDeleted(true)
          else setFailed(true)
        })
        .finally(() => setLoading(false)),
    [onUnauthorized],
  )

  useEffect(() => {
    void fetchPage()
  }, [fetchPage])

  function load(cursor?: string) {
    setLoading(true)
    setFailed(false)
    void fetchPage(cursor)
  }

  if (accountDeleted) return <p role="alert">{t('This account was deleted. Its data is gone from Averyn.')}</p>
  if (failed) {
    return (
      <p role="alert">
        {t('Could not load your activities.')} <button onClick={() => load()}>{t('Retry')}</button>
      </p>
    )
  }
  if (!loading && items.length === 0) {
    return (
      <p>
        {t('No activities yet. Record one in the app and it appears here after sync, or import a file.')}{' '}
        <button onClick={onImport}>{t('Import a file')}</button>
      </p>
    )
  }

  return (
    <>
      <p>
        <button onClick={onImport}>{t('Import a file')}</button>
      </p>
      <table>
        <thead>
          <tr>
            <th>{t('Date')}</th>
            <th>{t('Sport')}</th>
            <th>{t('Distance')}</th>
            <th>{t('Moving')}</th>
            <th>{t('Pace / speed')}</th>
            <th>{t('GPS quality')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((a) => (
            <tr key={a.id}>
              <td>
                <a
                  href={`/activities/${a.id}`}
                  onClick={(e) => {
                    e.preventDefault()
                    open(a.id)
                  }}
                >
                  {new Date(a.startedAt).toLocaleString()}
                </a>
                {a.finalState === 'FAILED' && ` (${t('interrupted')})`}
              </td>
              <td>{formatSport(a.sport)}</td>
              <td>{formatDistance(a.distanceM)}</td>
              <td>{formatDuration(a.movingMs)}</td>
              <td>{formatRate(a.sport, a).value}</td>
              <td>{formatQuality(a.qualityGrade)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {loading && <p>{t('Loading…')}</p>}
      {next && !loading && <button onClick={() => load(next)}>{t('Load more')}</button>}
    </>
  )
}
