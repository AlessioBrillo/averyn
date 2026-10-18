import { useEffect, useState } from 'react'
import { ApiError, deleteAccount, downloadExport, getConsents, revokeConsent } from './api'
import { signOut } from './auth'

/** The user's data: download everything, or delete it (TDD-0004). Consent for health-adjacent sensor data is
 *  server-authoritative and revocable here (ADR-0021). */
export function Account({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [consents, setConsents] = useState<{ scope: string; grantedAt: string; revokedAt: string | null }[]>([])
  const [consentsLoaded, setConsentsLoaded] = useState(false)

  function run(action: () => Promise<void>, message: string) {
    setBusy(true)
    setFailed(null)
    action()
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else setFailed(message)
      })
      .finally(() => setBusy(false))
  }

  useEffect(() => {
    getConsents()
      .then((grants) => {
        setConsents(grants)
        setConsentsLoaded(true)
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else setFailed('Could not load your consents. Try again.')
        setConsentsLoaded(true)
      })
  }, [onUnauthorized])

  const revoke = (scope: string) =>
    run(async () => {
      await revokeConsent(scope)
      setConsents((previous) =>
        previous.map((g) =>
          g.scope === scope ? { ...g, revokedAt: new Date().toISOString() } : g,
        ),
      )
    }, 'Could not revoke that consent. Try again.')

  const download = () =>
    run(async () => {
      const link = document.createElement('a')
      const url = URL.createObjectURL(await downloadExport())
      link.href = url
      link.download = 'averyn-export.zip'
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 60_000) // not at once: some browsers start the save asynchronously
    }, 'Could not download your data. Try again.')

  const remove = () =>
    run(async () => {
      await deleteAccount()
      await signOut()
    }, 'Could not delete your account. Try again.')

  return (
    <>
      <h2>Your data</h2>
      <section>
        <h3>Download</h3>
        <p>Every recording as it was captured, a GPX file for each, and a summary list, in one ZIP.</p>
        <button onClick={download} disabled={busy}>
          Download my data
        </button>
      </section>
      <section>
        <h3>Delete account</h3>
        <p>
          Removes all your activities and their recorded data from Averyn for good. Your sign-in at the identity
          provider is not removed: close it there if you want it gone. Download your data first.
        </p>
        <label>
          Type <strong>delete</strong> to confirm{' '}
          <input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} disabled={busy} />
        </label>{' '}
        <button onClick={remove} disabled={busy || confirmation.trim().toLowerCase() !== 'delete'}>
          Delete my account
        </button>
      </section>
      <section>
        <h3>Sensor data consent</h3>
        <p>
          Some sensors (for example a heart-rate strap) can record raw beat-to-beat intervals. That is health
          data: Averyn only stores it while you consent, per scope, and you can withdraw that consent at any
          time. Withdrawing stops new recordings immediately and deletes the intervals already uploaded.
        </p>
        {consentsLoaded ? (
          <table>
            <thead>
              <tr>
                <th>Scope</th>
                <th>Granted</th>
                <th>Revoked</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {consents.length === 0 && (
                <tr>
                  <td colSpan={4}>No consent grants yet.</td>
                </tr>
              )}
              {consents.map((g) => (
                <tr key={g.scope}>
                  <td>{g.scope}</td>
                  <td>{new Date(g.grantedAt).toLocaleString()}</td>
                  <td>{g.revokedAt == null ? '—' : new Date(g.revokedAt).toLocaleString()}</td>
                  <td>
                    {g.revokedAt == null ? (
                      <button onClick={() => revoke(g.scope)} disabled={busy}>
                        Revoke
                      </button>
                    ) : (
                      <span role="status">Revoked</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p>Loading…</p>
        )}
      </section>
      {failed && <p role="alert">{failed}</p>}
    </>
  )
}
