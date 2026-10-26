import { useEffect, useState } from 'react'
import type { UserProfile } from './api'
import {
  ApiError,
  deleteAccount,
  downloadExport,
  getConsents,
  getProfile,
  grantConsent,
  putProfile,
  revokeConsent,
} from './api'
import { signOut } from './auth'
import { t } from './i18n'
import { MAX_MAX_HR_BPM, MIN_MAX_HR_BPM, parseMaxHr } from './profile'

/** The word the user types to confirm the deletion of the account: the same in every language, like the button. */
const DELETE_WORD = 'delete'

/** The scope the user can grant here: the one the devices ask for (ADR-0021). */
const GRANTABLE_SCOPE = 'rr_recording'

/** The user's data: download everything, or delete it (TDD-0004). Consent for health-adjacent sensor data is
 *  server-authoritative and revocable here (ADR-0021). */
export function Account({
  onUnauthorized,
  onProfileSaved,
}: {
  onUnauthorized: () => void
  onProfileSaved: (profile: UserProfile) => void
}) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [consents, setConsents] = useState<{ scope: string; grantedAt: string; revokedAt: string | null }[]>([])
  const [consentsLoaded, setConsentsLoaded] = useState(false)
  const [units, setUnits] = useState<UserProfile['units']>('metric')
  const [maxHr, setMaxHr] = useState('')
  const [profileLoaded, setProfileLoaded] = useState(false)
  const [profileSaved, setProfileSaved] = useState(false)

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
    getProfile()
      .then((profile) => {
        setUnits(profile.units)
        setMaxHr(profile.maxHrBpm === null ? '' : String(profile.maxHrBpm))
        setProfileLoaded(true)
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else setFailed(t('Could not load your profile. Try again.'))
      })
  }, [onUnauthorized])

  useEffect(() => {
    getConsents()
      .then((grants) => {
        setConsents(grants)
        setConsentsLoaded(true)
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else setFailed(t('Could not load your consents. Try again.'))
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
    }, t('Could not revoke that consent. Try again.'))

  const grant = (scope: string) =>
    run(async () => {
      await grantConsent(scope)
      setConsents((previous) => {
        const now = new Date().toISOString()
        const others = previous.filter((g) => g.scope !== scope)
        return [...others, { scope, grantedAt: now, revokedAt: null }]
      })
    }, t('Could not grant that consent. Try again.'))

  const parsedMaxHr = parseMaxHr(maxHr)

  const saveProfile = () => {
    if (!parsedMaxHr.ok) return
    run(async () => {
      const saved = await putProfile({ units, maxHrBpm: parsedMaxHr.value })
      setProfileSaved(true)
      onProfileSaved(saved)
    }, t('Could not save your profile. Try again.'))
  }

  const download = () =>
    run(async () => {
      const link = document.createElement('a')
      const url = URL.createObjectURL(await downloadExport())
      link.href = url
      link.download = 'averyn-export.zip'
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 60_000) // not at once: some browsers start the save asynchronously
    }, t('Could not download your data. Try again.'))

  const remove = () =>
    run(async () => {
      await deleteAccount()
      await signOut()
    }, t('Could not delete your account. Try again.'))

  return (
    <>
      <h2>{t('Your data')}</h2>
      <section>
        <h3>{t('Profile')}</h3>
        <p>
          {t(
            'The unit system is used for every distance, pace and elevation. Your maximum heart rate is kept with your account, deleted with it and included in your download.',
          )}
        </p>
        <label>
          {t('Units')}{' '}
          <select
            value={units}
            onChange={(e) => {
              setUnits(e.target.value as UserProfile['units'])
              setProfileSaved(false)
            }}
            disabled={busy || !profileLoaded}
          >
            <option value="metric">{t('Metric (km, m)')}</option>
            <option value="imperial">{t('Imperial (mi, ft)')}</option>
          </select>
        </label>{' '}
        <label>
          {t('Maximum heart rate (bpm)')}{' '}
          <input
            inputMode="numeric"
            value={maxHr}
            placeholder={`${MIN_MAX_HR_BPM}–${MAX_MAX_HR_BPM}`}
            onChange={(e) => {
              setMaxHr(e.target.value)
              setProfileSaved(false)
            }}
            disabled={busy || !profileLoaded}
            aria-invalid={!parsedMaxHr.ok}
          />
        </label>{' '}
        <button onClick={saveProfile} disabled={busy || !profileLoaded || !parsedMaxHr.ok}>
          {t('Save profile')}
        </button>
        {!parsedMaxHr.ok && (
          <p role="alert">
            {t('The maximum heart rate must be a whole number from {min} to {max}, or empty.', {
              min: MIN_MAX_HR_BPM,
              max: MAX_MAX_HR_BPM,
            })}
          </p>
        )}
        {profileSaved && <p role="status">{t('Saved.')}</p>}
      </section>
      <section>
        <h3>{t('Download')}</h3>
        <p>{t('Every recording as it was captured, a GPX file for each, and a summary list, in one ZIP.')}</p>
        <button onClick={download} disabled={busy}>
          {t('Download my data')}
        </button>
      </section>
      <section>
        <h3>{t('Delete account')}</h3>
        <p>
          {t(
            'Removes all your activities and their recorded data from Averyn for good. Your sign-in at the identity provider is not removed: close it there if you want it gone. Download your data first.',
          )}
        </p>
        <label>
          {t('Type {word} to confirm', { word: DELETE_WORD })}{' '}
          <input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} disabled={busy} />
        </label>{' '}
        <button onClick={remove} disabled={busy || confirmation.trim().toLowerCase() !== DELETE_WORD}>
          {t('Delete my account')}
        </button>
      </section>
      <section>
        <h3>{t('Sensor data consent')}</h3>
        <p>
          {t(
            'Some sensors (for example a heart-rate strap) can record raw beat-to-beat intervals. That is health data: Averyn only stores it while you consent, per scope, and you can withdraw that consent at any time. Withdrawing stops new recordings immediately and deletes the intervals already uploaded.',
          )}
        </p>
        {consentsLoaded ? (
          <table>
            <thead>
              <tr>
                <th>{t('Scope')}</th>
                <th>{t('Granted')}</th>
                <th>{t('Revoked')}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {consents.length === 0 && (
                <tr>
                  <td colSpan={4}>{t('No consent grants yet.')}</td>
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
                        {t('Revoke')}
                      </button>
                    ) : (
                      <span role="status">{t('Revoked')}</span>
                    )}
                  </td>
                </tr>
              ))}
              {!consents.some((g) => g.scope === GRANTABLE_SCOPE) && (
                <tr>
                  <td>{GRANTABLE_SCOPE}</td>
                  <td>—</td>
                  <td>—</td>
                  <td>
                    <button onClick={() => grant(GRANTABLE_SCOPE)} disabled={busy}>
                      {t('Grant')}
                    </button>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        ) : (
          <p>{t('Loading…')}</p>
        )}
      </section>
      {failed && <p role="alert">{failed}</p>}
    </>
  )
}
