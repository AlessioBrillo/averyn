import { useCallback, useEffect, useState } from 'react'
import { ApiError, listActivities } from './api'
import type { ActivitySummary } from './api'
import { formatDistance, formatDuration, formatRate } from './format'

export function ActivityList({
  open,
  onUnauthorized,
}: {
  open: (id: string) => void
  /** Must be stable (useCallback): it is an effect dependency. */
  onUnauthorized: () => void
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

  if (accountDeleted) return <p role="alert">This account was deleted. Its data is gone from Averyn.</p>
  if (failed) {
    return (
      <p role="alert">
        Could not load your activities. <button onClick={() => load()}>Retry</button>
      </p>
    )
  }
  if (!loading && items.length === 0) return <p>No activities yet. Record one in the app and it appears here after sync.</p>

  return (
    <>
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Sport</th>
            <th>Distance</th>
            <th>Moving</th>
            <th>Pace / speed</th>
            <th>GPS quality</th>
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
                {a.finalState === 'FAILED' && ' (interrupted)'}
              </td>
              <td>{a.sport.toLowerCase()}</td>
              <td>{formatDistance(a.distanceM)}</td>
              <td>{formatDuration(a.movingMs)}</td>
              <td>{formatRate(a.sport, a).value}</td>
              <td>{a.qualityGrade.toLowerCase()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {loading && <p>Loading…</p>}
      {next && !loading && <button onClick={() => load(next)}>Load more</button>}
    </>
  )
}
