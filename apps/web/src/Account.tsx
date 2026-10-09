import { useState } from 'react'
import { ApiError, deleteAccount, downloadExport } from './api'
import { signOut } from './auth'

/** The user's data: download everything, or delete it (TDD-0004). */
export function Account({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [confirmation, setConfirmation] = useState('')

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
      {failed && <p role="alert">{failed}</p>}
    </>
  )
}
