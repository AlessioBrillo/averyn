import { useEffect, useState } from 'react'
import { ApiError, getRecords } from './api'
import type { PersonalRecords } from './api'
import { formatDuration, formatQuality, formatRate, formatTargetDistance } from './format'

/**
 * The personal records of the account (TDD-0010): the fastest effort of every distance of every sport.
 *
 * The browser draws what the server computed: it never computes a record, and it never compares two
 * activities by itself.
 */
export function Records({
  onUnauthorized,
  open,
}: {
  /** Must be stable (useCallback): it is an effect dependency. */
  onUnauthorized: () => void
  /** Opens the activity that holds a record. */
  open: (id: string) => void
}) {
  const [records, setRecords] = useState<PersonalRecords | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [accountDeleted, setAccountDeleted] = useState(false)

  useEffect(() => {
    let current = true
    getRecords().then(
      (value) => {
        if (!current) return
        setRecords(value)
        setLoading(false)
      },
      (e: unknown) => {
        if (!current) return
        setLoading(false)
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else if (e instanceof ApiError && e.status === 403) setAccountDeleted(true)
        else setFailed(true)
      },
    )
    return () => {
      current = false
    }
  }, [onUnauthorized])

  if (accountDeleted) return <p role="alert">This account was deleted. Its data is gone from Averyn.</p>
  if (failed) return <p role="alert">Could not load your records. Try again in a moment.</p>
  if (loading) return <p>Loading…</p>
  if (!records) return <p>Loading…</p>

  return <RecordsView records={records} open={open} />
}

/** The records themselves, without the fetching: what the page shows. */
export function RecordsView({
  records,
  open,
}: {
  records: PersonalRecords
  open: (id: string) => void
}) {
  if (records.sports.length === 0) {
    return (
      <>
        <h2>Records</h2>
        <p>
          No record yet. A record is the fastest kilometre (or five, or ten) of one of your activities: record
          something, and it will be here.
        </p>
      </>
    )
  }

  return (
    <>
      <h2>Records</h2>
      {records.pendingActivities > 0 && (
        <p role="status">
          {records.pendingActivities} of your activities have not been analysed yet: the records below can be
          incomplete. Open them, or run the backfill of the server, and they will be complete.
        </p>
      )}
      {records.sports.map((sport) => (
        <section key={sport.sport} aria-labelledby={`records-${sport.sport}`}>
          <h3 id={`records-${sport.sport}`}>{sport.sport.toLowerCase()}</h3>
          <div className="table-scroll">
            <table>
              <caption className="visually-hidden">The records of {sport.sport.toLowerCase()}</caption>
              <thead>
                <tr>
                  <th scope="col">Distance</th>
                  <th scope="col">Time</th>
                  <th scope="col">{sport.sport === 'RIDE' ? 'Speed' : 'Pace'}</th>
                  <th scope="col">When</th>
                  <th scope="col">Activity</th>
                </tr>
              </thead>
              <tbody>
                {sport.targets.map((target) => {
                  const record = target.record
                  return (
                    <tr key={target.distanceM}>
                      <th scope="row">{formatTargetDistance(target.distanceM)}</th>
                      <td>{record ? formatDuration(record.durationMs) : '—'}</td>
                      <td>
                        {record
                          ? formatRate(sport.sport, {
                              averageSpeedMps: record.avgSpeedMps,
                              paceSecPerKm: record.paceSecPerKm,
                            }).value
                          : '—'}
                      </td>
                      <td>
                        {record ? new Date(record.achievedAt).toLocaleDateString() : '—'}
                      </td>
                      <td>
                        {record ? (
                          <a
                            href={`/activities/${record.activityId}`}
                            onClick={(e) => {
                              e.preventDefault()
                              open(record.activityId)
                            }}
                          >
                            {record.source === 'device' ? 'recorded' : 'imported'} ·{' '}
                            {formatQuality(record.qualityGrade)}
                          </a>
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </section>
      ))}
      <p className="small">
        The time of a record is the time the athlete was moving. Algorithms: {records.algorithmVersion}
      </p>
    </>
  )
}
